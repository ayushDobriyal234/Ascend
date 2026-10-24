package com.ayush.ascend.core

import com.ayush.ascend.data.WeeklyReport
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Weekly report card: the 7 days ending [end] vs the 7 before. */
object Report {

    suspend fun build(g: Graph, end: LocalDate = LocalDate.now()): WeeklyReport {
        Progress.backfill(g)
        val goal = g.prefs.current().dailyGoalMin
        val days = (6 downTo 0).map { end.minusDays(it.toLong()) }
        val usage = days.map { g.usage.day(it) }
        val prev = (13 downTo 7).map { g.usage.day(end.minusDays(it.toLong())) }
        val stats = days.mapNotNull { g.db.days().get(it.toString()) }

        val total = usage.sumOf { it.totalMinutes }
        val unlocks = usage.sumOf { it.unlocks }
        val minutes = days.zip(usage.map { it.totalMinutes })
        val best = minutes.minBy { it.second }
        val worst = minutes.maxBy { it.second }
        val perApp = HashMap<String, Long>()
        usage.forEach { u -> u.perAppMs.forEach { (p, ms) -> perApp[p] = (perApp[p] ?: 0L) + ms } }
        val top = perApp.entries.sortedByDescending { it.value }.take(3).joinToString(";") { "${it.key}=${it.value / 60_000}" }

        val underGoal = minutes.count { it.second <= goal }
        val summits = stats.count { it.summit }
        val trend = pct(total, prev.sumOf { it.totalMinutes })
        val unlockTrend = pct(unlocks, prev.sumOf { it.unlocks })
        val from = g.usage.startOfDay(days.first())

        return WeeklyReport(
            weekStart = days.first().toString(),
            createdAt = System.currentTimeMillis(),
            // today has no DayStat yet, so summit share is over the days we know
            grade = grade(underGoal, if (stats.isEmpty()) 0.0 else summits / stats.size.toDouble(), trend, unlockTrend),
            totalMinutes = total,
            avgMinutes = total / 7,
            bestDay = best.first.toString(), bestMinutes = best.second,
            worstDay = worst.first.toString(), worstMinutes = worst.second,
            topApps = top,
            trendPct = trend,
            unlocks = unlocks, unlocksTrendPct = unlockTrend,
            daysUnderGoal = underGoal, summitDays = summits,
            streak = Progress.streak(g.db.days().recent(60)),
            mood = Mood.summary(g.db.moods().since(from)),
            timeCost = TimeCost.line(total),
        )
    }

    /** % change vs before; 0 when there's nothing to compare against. */
    fun pct(now: Int, before: Int): Int = if (before <= 0) 0 else ((now - before) * 100.0 / before).roundToInt()

    /**
     * 100-point score → A+…F. Days under goal 35, summit days 30, screen-time trend 20, unlocks trend 15.
     * Trends score linearly: 20 % down = full points, flat = half, 20 % up = none.
     */
    fun grade(daysUnderGoal: Int, summitShare: Double, trendPct: Int, unlocksTrendPct: Int): String {
        fun trend(p: Int) = (20 - p.coerceIn(-20, 20)) / 40.0
        val score = 35 * daysUnderGoal.coerceIn(0, 7) / 7.0 + 30 * summitShare.coerceIn(0.0, 1.0) +
            20 * trend(trendPct) + 15 * trend(unlocksTrendPct)
        return GRADES.firstOrNull { score >= it.first }?.second ?: "F"
    }

    private val GRADES = listOf(95 to "A+", 88 to "A", 80 to "A-", 75 to "B+", 68 to "B", 60 to "B-",
        55 to "C+", 48 to "C", 40 to "C-", 30 to "D")

    /** "↓ 12%" (less is good) or "↑ 8%". */
    fun arrow(p: Int) = when { p < 0 -> "↓ ${-p}%"; p > 0 -> "↑ $p%"; else -> "→ 0%" }

    fun dayName(day: String): String = runCatching {
        LocalDate.parse(day).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }.getOrDefault(day)
}
