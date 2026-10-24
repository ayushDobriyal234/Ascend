package com.ayush.ascend.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

data class DayUsage(
    val perAppMs: Map<String, Long>,
    val totalMs: Long,
    val unlocks: Int,
    val start: Long,
    val end: Long,
) {
    val totalMinutes get() = (totalMs / 60_000).toInt()
    fun minutes(pkg: String) = ((perAppMs[pkg] ?: 0L) / 60_000).toInt()
    /** Pickups per waking hour so far. */
    val pickupsPerHour: Double get() {
        val hours = ((end - start) / 3_600_000.0).coerceAtLeast(1.0)
        return unlocks / hours
    }
}

data class InstalledApp(val pkg: String, val label: String)

/** #1 Usage tracker — reconstructs foreground time from raw UsageEvents (more accurate than aggregated stats). */
class UsageRepo(private val ctx: Context) {

    private val usm = ctx.getSystemService(UsageStatsManager::class.java)
    private val pm = ctx.packageManager
    private val labelCache = ConcurrentHashMap<String, String>()
    @Volatile private var todayCache: DayUsage? = null
    @Volatile private var todayCachedAt = 0L

    private val ignored: Set<String> by lazy {
        val homes = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL
        ).map { it.activityInfo.packageName }
        (homes + ctx.packageName + "com.android.systemui").toSet()
    }

    fun hasAccess(): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun startOfDay(date: LocalDate = LocalDate.now()): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Today's usage, cached for [maxAgeMs] so the accessibility tick stays cheap. */
    fun today(maxAgeMs: Long = 10_000): DayUsage {
        val now = System.currentTimeMillis()
        val c = todayCache
        if (c != null && now - todayCachedAt < maxAgeMs && c.start == startOfDay()) return c
        return between(startOfDay(), now, live = true).also { todayCache = it; todayCachedAt = now }
    }

    fun invalidate() { todayCachedAt = 0 }

    fun day(date: LocalDate): DayUsage {
        val s = startOfDay(date)
        val e = startOfDay(date.plusDays(1))
        val now = System.currentTimeMillis()
        return between(s, minOf(e, now), live = e > now)
    }

    fun between(start: Long, end: Long, live: Boolean = false): DayUsage {
        if (!hasAccess()) return DayUsage(emptyMap(), 0, 0, start, end)
        val events = usm.queryEvents(start, end)
        val per = HashMap<String, Long>()
        var fg: String? = null
        var fgStart = 0L
        var unlocks = 0
        val e = UsageEvents.Event()
        fun close(at: Long) {
            val p = fg ?: return
            if (p !in ignored) per[p] = (per[p] ?: 0L) + (at - fgStart).coerceAtLeast(0)
            fg = null
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                1 /* ACTIVITY_RESUMED */ -> if (fg != e.packageName) {
                    close(e.timeStamp); fg = e.packageName; fgStart = e.timeStamp
                }
                2 /* ACTIVITY_PAUSED */ -> if (fg == e.packageName) close(e.timeStamp)
                16 /* SCREEN_NON_INTERACTIVE */, 26 /* DEVICE_SHUTDOWN */ -> close(e.timeStamp)
                18 /* KEYGUARD_HIDDEN */ -> unlocks++
            }
        }
        val interactive = ctx.getSystemService(PowerManager::class.java).isInteractive
        if (fg != null) { if (live && interactive) close(end) else fg = null }
        return DayUsage(per, per.values.sum(), unlocks, start, end)
    }

    fun label(pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    fun icon(pkg: String): Drawable? = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()

    fun launchableApps(): List<InstalledApp> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != ctx.packageName }
            .map { InstalledApp(it, label(it)) }
            .sortedBy { it.label.lowercase() }

    fun launch(pkg: String): Boolean {
        val i = pm.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

fun formatMinutes(min: Int): String = when {
    min < 60 -> "${min}m"
    min % 60 == 0 -> "${min / 60}h"
    else -> "${min / 60}h ${min % 60}m"
}
