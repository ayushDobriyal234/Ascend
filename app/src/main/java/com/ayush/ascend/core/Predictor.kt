package com.ayush.ascend.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.ayush.ascend.data.Feature
import com.ayush.ascend.data.UnlockSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * #9 Relapse predictor.
 *
 * Every unlock → snapshot 9 features. 20 min later it's labelled "binge" if you spent ≥10 min in
 * distracting apps. A logistic-regression model is trained on-device (nightly + on demand) with
 * class-balanced SGD. On unlock, if P(binge) ≥ 0.7 Ascend goes into "high-risk" mode for 20 min:
 * every distracting app is gated and friction gets +1 level — friction lands BEFORE the scroll.
 */
class Predictor(private val g: Graph) {

    companion object {
        const val N = 9
        const val THRESHOLD = 0.7
        const val WINDOW_MS = 20 * 60_000L
        const val BINGE_MS = 10 * 60_000L
        const val MIN_SAMPLES = 40
        val FEATURE_NAMES = listOf("bias", "hour·sin", "hour·cos", "weekend", "battery", "charging",
            "since last unlock", "unlocks last hour", "last app distracting")
    }

    private val _risk = MutableStateFlow(0.0)
    val risk: StateFlow<Double> = _risk
    @Volatile private var riskUntil = 0L
    @Volatile private var lastUnlockWall = 0L
    private val recentUnlocks = ArrayDeque<Long>()

    fun highRisk(): Boolean = SystemClock.elapsedRealtime() < riskUntil

    fun features(ctx: Context, at: LocalDateTime, minsSinceLast: Double, unlocksLastHour: Int, lastDistracting: Boolean): DoubleArray {
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0) l / s.toDouble() else 0.5
        } ?: 0.5
        val charging = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return featureVector(at.hour + at.minute / 60.0, at.dayOfWeek, level, charging, minsSinceLast, unlocksLastHour, lastDistracting)
    }

    fun featureVector(hour: Double, dow: DayOfWeek, battery: Double, charging: Boolean, minsSinceLast: Double, unlocksLastHour: Int, lastDistracting: Boolean) =
        doubleArrayOf(
            1.0,
            sin(2 * PI * hour / 24), cos(2 * PI * hour / 24),
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) 1.0 else 0.0,
            battery,
            if (charging) 1.0 else 0.0,
            ln(1 + minsSinceLast.coerceAtLeast(0.0)) / 5.0,
            unlocksLastHour / 10.0,
            if (lastDistracting) 1.0 else 0.0,
        )

    /** Called by the guard on every ACTION_USER_PRESENT. */
    suspend fun onUnlock(ctx: Context, lastFgPkg: String?) {
        val now = System.currentTimeMillis()
        val mins = if (lastUnlockWall == 0L) 30.0 else (now - lastUnlockWall) / 60_000.0
        lastUnlockWall = now
        synchronized(recentUnlocks) {
            recentUnlocks.addLast(now)
            while (recentUnlocks.isNotEmpty() && recentUnlocks.first() < now - 3_600_000) recentUnlocks.removeFirst()
        }
        val distracting = distractingSet()
        val x = features(ctx, LocalDateTime.now(), mins, recentUnlocks.size - 1, lastFgPkg in distracting)
        g.db.samples().insert(UnlockSample(ts = now, features = x.joinToString(",")))

        val s = g.prefs.current()
        val w = parseWeights(s.modelWeights)
        if (!s.on(Feature.PREDICTOR) || w == null) { _risk.value = 0.0; return }
        val p = predict(w, x)
        _risk.value = p
        if (p >= THRESHOLD) riskUntil = SystemClock.elapsedRealtime() + WINDOW_MS
    }

    suspend fun distractingSet(): Set<String> = g.db.rules().all().map { it.pkg }.toSet() + KNOWN_DISTRACTING

    fun predict(w: DoubleArray, x: DoubleArray): Double {
        var z = 0.0
        for (i in 0 until N) z += w[i] * x[i]
        return 1 / (1 + exp(-z))
    }

    fun parseWeights(csv: String): DoubleArray? =
        csv.split(',').mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == N }?.toDoubleArray()

    /** Typical-weekday risk for each hour — powers the Insights heat-strip. */
    fun hourlyRisk(w: DoubleArray): List<Double> = (0 until 24).map { h ->
        predict(w, featureVector(h + 0.5, DayOfWeek.WEDNESDAY, 0.6, false, 25.0, 3, false))
    }

    /** Labels matured samples and retrains. Returns (accuracy, samples) or null if not enough data. */
    suspend fun labelAndTrain(): Pair<Double, Int>? {
        val now = System.currentTimeMillis()
        val distracting = distractingSet()
        for (s in g.db.samples().unlabeled(now - WINDOW_MS)) {
            val u = g.usage.between(s.ts, s.ts + WINDOW_MS)
            val ms = u.perAppMs.filterKeys { it in distracting }.values.sum()
            g.db.samples().setLabel(s.id, if (ms >= BINGE_MS) 1 else 0)
        }
        g.db.samples().prune(now - 90L * 24 * 3_600_000)

        val data = g.db.samples().labeled(now - 60L * 24 * 3_600_000).mapNotNull { s ->
            s.features.split(',').mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == N }?.toDoubleArray()?.let { it to s.label }
        }
        val pos = data.count { it.second == 1 }
        if (data.size < MIN_SAMPLES || pos == 0 || pos == data.size) return null

        val (w, acc) = train(data)
        g.prefs.update { p, k ->
            p[k.weights] = w.joinToString(",")
            p[k.acc] = acc.toString()
            p[k.samples] = data.size
        }
        return acc to data.size
    }

    /** Class-balanced logistic regression, L2, SGD with decaying LR. 80/20 split for honest accuracy. */
    fun train(data: List<Pair<DoubleArray, Int>>): Pair<DoubleArray, Double> {
        val shuffled = data.shuffled(kotlin.random.Random(42))
        val cut = (shuffled.size * 0.8).toInt().coerceAtLeast(1)
        val trainSet = shuffled.take(cut)
        val test = shuffled.drop(cut).ifEmpty { trainSet }
        val pos = trainSet.count { it.second == 1 }.coerceAtLeast(1)
        val neg = (trainSet.size - pos).coerceAtLeast(1)
        val wPos = trainSet.size / (2.0 * pos)
        val wNeg = trainSet.size / (2.0 * neg)
        val w = DoubleArray(N)
        val l2 = 1e-3
        repeat(300) { epoch ->
            val lr = 0.1 / (1 + epoch * 0.02)
            for ((x, y) in trainSet.shuffled()) {
                val err = predict(w, x) - y
                val cw = if (y == 1) wPos else wNeg
                for (i in 0 until N) w[i] -= lr * (cw * err * x[i] + if (i == 0) 0.0 else l2 * w[i])
            }
        }
        val acc = test.count { (x, y) -> (predict(w, x) >= 0.5) == (y == 1) } / test.size.toDouble()
        return w to acc
    }
}
