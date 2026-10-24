package com.ayush.ascend.core

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.random.Random

data class TaskState(val id: String, val title: String, val reward: Int, val progress: Int, val target: Int, val claimed: Boolean) {
    val done get() = progress >= target
}

data class Offer(val minutes: Int, val cost: Int)
data class Chest(val points: Int, val minutes: Int)

/**
 * AC points (shown as coins): earned from daily tasks + the daily chest, spent in the shop on bonus minutes.
 * Bonus minutes only unlock from the limit-reached screen, i.e. after an app's daily limit is used up.
 * The limit itself (and its 45-day lock) is never touched.
 */
class Wallet(private val g: Graph) {

    companion object {
        val OFFERS = listOf(Offer(5, 10_000), Offer(15, 27_000), Offer(30, 50_000))
        const val BONUS_CHUNK = 5
        const val CHEST_MIN = 30
        const val CHEST_MAX = 150
        const val CHEST_MINUTES_CHANCE = 5   // %
    }

    /** Day key from the tamper-safe clock, so moving the phone date doesn't refill tasks/chest. */
    suspend fun today(): LocalDate = Instant.ofEpochMilli(g.clock.now()).atZone(ZoneId.systemDefault()).toLocalDate()

    suspend fun tasks(): List<TaskState> {
        val day = today()
        val s = g.prefs.current()
        val steps = sampleSteps()
        Progress.backfill(g)
        val y = g.db.days().get(day.minusDays(1).toString()).takeIf { g.usage.hasAccess() }
        fun t(id: String, title: String, reward: Int, progress: Int, target: Int) =
            TaskState(id, title, reward, progress, target, "$id@$day" in s.claimed)
        return listOf(
            t("steps10k", "Walk 10,000 steps", 300, steps, 10_000),
            t("steps5k", "Walk 5,000 steps", 120, steps, 5_000),
            t("summit", "Yesterday was a summit day", 200, if (y?.summit == true) 1 else 0, 1),
            t("goal", "Yesterday under your ${s.dailyGoalMin / 60}h goal", 150, if (y != null && y.totalMinutes <= s.dailyGoalMin) 1 else 0, 1),
            t("unlocks", "Under 60 unlocks yesterday", 100, if (y != null && y.unlocks < 60) 1 else 0, 1),
        )
    }

    suspend fun claim(task: TaskState): Boolean {
        if (!task.done) return false
        val key = "${task.id}@${today()}"
        var ok = false
        g.prefs.update { p, k ->
            val set = p[k.claimed].orEmpty()
            if (key in set) return@update
            p[k.claimed] = set.filter { it.endsWith(key.substringAfter('@')) }.toSet() + key  // drop older days
            p[k.ac] = (p[k.ac] ?: 0) + task.reward
            ok = true
        }
        return ok
    }

    suspend fun chestReady(): Boolean = g.prefs.current().chestDay != today().toString()

    /** Null if today's chest was already opened. */
    suspend fun openChest(): Chest? {
        val day = today().toString()
        var out: Chest? = null
        g.prefs.update { p, k ->
            if (p[k.chestDay] == day) return@update
            val c = Chest(
                Random.nextInt(CHEST_MIN, CHEST_MAX + 1),
                if (Random.nextInt(100) < CHEST_MINUTES_CHANCE) Random.nextInt(5, 16) else 0,
            )
            p[k.chestDay] = day
            p[k.ac] = (p[k.ac] ?: 0) + c.points
            p[k.bonus] = (p[k.bonus] ?: 0) + c.minutes
            out = c
        }
        return out
    }

    suspend fun buy(o: Offer): Boolean {
        var ok = false
        g.prefs.update { p, k ->
            val ac = p[k.ac] ?: 0
            if (ac < o.cost) return@update
            p[k.ac] = ac - o.cost
            p[k.bonus] = (p[k.bonus] ?: 0) + o.minutes
            ok = true
        }
        return ok
    }

    /** Takes up to [max] bonus minutes from the balance; returns how many were taken. */
    suspend fun takeBonus(max: Int): Int {
        var taken = 0
        g.prefs.update { p, k ->
            val b = p[k.bonus] ?: 0
            taken = minOf(b, max)
            p[k.bonus] = b - taken
        }
        return taken
    }

    // ---------------- steps ----------------

    fun hasStepPermission(): Boolean = Build.VERSION.SDK_INT < 29 ||
        g.app.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /**
     * Steps today from the hardware step counter (counts since boot). Sampled every minute by the guard,
     * so the day's baseline is set right after midnight; survives reboots by accumulating before the reset.
     */
    suspend fun sampleSteps(): Int {
        val day = today().toString()
        val cur = readCounter()
        var steps = 0
        g.prefs.update { p, k ->
            if (cur == null) {
                if (p[k.stepDay] == day) steps = ((p[k.stepAccum] ?: 0L) + (p[k.stepLast] ?: 0L) - (p[k.stepBase] ?: 0L)).toInt()
                return@update
            }
            var base = p[k.stepBase] ?: cur
            var acc = p[k.stepAccum] ?: 0L
            val last = p[k.stepLast] ?: cur
            if (cur < last) { acc += last - base; base = 0 }  // rebooted: counter restarted at 0
            if (p[k.stepDay] != day) { acc = 0; base = cur }
            p[k.stepDay] = day; p[k.stepBase] = base; p[k.stepAccum] = acc; p[k.stepLast] = cur
            steps = (acc + cur - base).toInt()
        }
        return steps
    }

    private suspend fun readCounter(): Long? {
        if (!hasStepPermission()) return null
        val sm = g.app.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { c ->
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (c.isActive) c.resume(e.values[0].toLong())
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
                }
                sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                c.invokeOnCancellation { sm.unregisterListener(l) }
            }
        }
    }
}
