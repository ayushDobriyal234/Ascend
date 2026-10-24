package com.ayush.ascend.core

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Gate sessions — granted after passing the intent gate. Uses elapsedRealtime so clock changes don't extend them. */
object Sessions {
    private val until = ConcurrentHashMap<String, Long>()

    fun grant(pkg: String, minutes: Int) { until[pkg] = SystemClock.elapsedRealtime() + minutes * 60_000L }
    fun active(pkg: String): Boolean = (until[pkg] ?: 0L) > SystemClock.elapsedRealtime()
    fun remainingMs(pkg: String): Long = ((until[pkg] ?: 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    fun expired(pkg: String): Boolean = until.containsKey(pkg) && !active(pkg)
    fun clear(pkg: String) { until.remove(pkg) }
}

data class FrictionPlan(
    val level: Int,
    val waitSeconds: Int,
    val sentence: String?,
    val mathA: Int?,
    val mathB: Int?,
) {
    val mathAnswer: Int? get() = if (mathA != null && mathB != null) mathA * mathB else null
}

/** #4 Escalating friction — each reopen within an hour costs more. */
class Friction(private val g: Graph) {

    suspend fun plan(pkg: String, label: String, highRisk: Boolean): FrictionPlan {
        val since = System.currentTimeMillis() - 60 * 60_000L
        val opens = g.db.opens().gateOpensSince(pkg, since)
        val level = opens + if (highRisk) 1 else 0
        return when {
            level <= 0 -> FrictionPlan(0, 5, null, null, null)
            level == 1 -> FrictionPlan(1, 10, null, null, null)
            level == 2 -> FrictionPlan(2, 30, null, null, null)
            level == 3 -> FrictionPlan(3, 45, "I am choosing $label over what actually matters right now.", null, null)
            else -> FrictionPlan(level, 60, null, Random.nextInt(12, 49), Random.nextInt(13, 89))
        }
    }
}
