package com.ayush.ascend.core

import com.ayush.ascend.data.DayStat
import com.ayush.ascend.data.DayUsage
import java.time.LocalDate
import java.time.ZoneId

/**
 * The climb: every "summit day" (all limits respected, no paid unlocks) = +100 m.
 * 45 days in a row = 4,500 m = the summit — same length as the limit lock.
 */
object Progress {
    const val METERS_PER_DAY = 100
    const val SUMMIT_DAYS = LOCK_DAYS

    suspend fun backfill(g: Graph) {
        val install = runCatching {
            g.app.packageManager.getPackageInfo(g.app.packageName, 0).firstInstallTime
        }.getOrDefault(0L)
        val installDay = java.time.Instant.ofEpochMilli(install).atZone(ZoneId.systemDefault()).toLocalDate()
        val rules = g.db.rules().all()
        val today = LocalDate.now()
        for (i in 1..30) {
            val d = today.minusDays(i.toLong())
            if (d.isBefore(installDay)) break
            if (g.db.days().get(d.toString()) != null) continue
            val u = g.usage.day(d)
            g.db.days().upsert(DayStat(d.toString(), u.totalMinutes, u.unlocks, isSummit(g, d, u, rules)))
        }
    }

    private suspend fun isSummit(g: Graph, d: LocalDate, u: DayUsage, rules: List<com.ayush.ascend.data.AppRule>): Boolean {
        val from = g.usage.startOfDay(d); val to = g.usage.startOfDay(d.plusDays(1))
        if (g.db.opens().stakesBetween(from, to) > 0) return false
        // only rules that existed that day count
        return rules.filter { it.limitMinutes != null && it.createdAt < to }
            .all { u.minutes(it.pkg) <= it.limitMinutes!! + 1 }
    }

    /** Consecutive summit days ending yesterday. */
    fun streak(days: List<DayStat>): Int {
        val byDay = days.associateBy { it.day }
        var d = LocalDate.now().minusDays(1)
        var n = 0
        while (byDay[d.toString()]?.summit == true) { n++; d = d.minusDays(1) }
        return n
    }

    fun altitude(streak: Int) = streak * METERS_PER_DAY
}
