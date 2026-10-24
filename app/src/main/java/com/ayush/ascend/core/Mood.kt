package com.ayush.ascend.core

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ayush.ascend.AscendApp
import com.ayush.ascend.R
import com.ayush.ascend.data.MoodEntry
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** Mood check-in: "how did that feel?" after a distracting session. Never blocks, just a notification. */
object Mood {
    const val MAX_PER_DAY = 4
    const val MIN_MINUTES = 3
    const val MIN_ENTRIES = 5
    private const val NOTIF_ID = 4301
    val faces = mapOf(1 to "😊", 0 to "😐", -1 to "😞")

    /** Called when you leave a distracting app (incl. a gate session ending). */
    suspend fun maybeAsk(ctx: Context, pkg: String, minutes: Int) {
        if (minutes < MIN_MINUTES) return
        val g = ctx.graph
        if (Grayscale.inBedtime(g.prefs.current())) return
        val sp = ctx.getSharedPreferences("mood", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        val asked = if (sp.getString("day", "") == today) sp.getInt("n", 0) else 0
        if (asked >= MAX_PER_DAY) return
        sp.edit().putString("day", today).putInt("n", asked + 1).apply()

        fun action(mood: Int) = NotificationCompat.Action(0, faces[mood], PendingIntent.getBroadcast(ctx, 300 + mood,
            Intent(ctx, MoodReceiver::class.java).putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_MOOD, mood).putExtra(EXTRA_MIN, minutes),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        val n = NotificationCompat.Builder(ctx, AscendApp.CH_MOOD)
            .setSmallIcon(R.drawable.ic_monochrome)
            .setContentTitle("How do you feel after ${g.usage.label(pkg)}?")
            .setContentText("$minutes min · tap one, or just ignore this")
            .addAction(action(1)).addAction(action(0)).addAction(action(-1))
            .setAutoCancel(true)
            .setTimeoutAfter(30 * 60_000L)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
    }

    /** Percent of 😊 / 😐 / 😞 for one app. */
    data class AppMood(val pkg: String, val count: Int, val pct: Map<Int, Int>)

    fun byApp(entries: List<MoodEntry>): List<AppMood> = entries.groupBy { it.pkg }
        .map { (pkg, list) -> AppMood(pkg, list.size, faces.keys.associateWith { m -> pct(list.count { it.mood == m }, list.size) }) }
        .sortedByDescending { it.count }

    /** "Instagram leaves you 😞 70% of the time" — the strongest single mood among apps with enough entries. */
    fun highlight(apps: List<AppMood>, label: (String) -> String): String? = apps.filter { it.count >= MIN_ENTRIES }
        .flatMap { a -> a.pct.map { (m, p) -> Triple(a.pkg, m, p) } }
        .maxByOrNull { it.third }
        ?.let { (pkg, m, p) -> "${label(pkg)} leaves you ${faces[m]} $p% of the time" }

    fun summary(entries: List<MoodEntry>): String {
        if (entries.isEmpty()) return "No check-ins"
        return faces.entries.joinToString(" · ") { (m, f) -> "$f ${pct(entries.count { it.mood == m }, entries.size)}%" }
    }

    private fun pct(n: Int, of: Int) = if (of == 0) 0 else (n * 100.0 / of).roundToInt()

    const val EXTRA_PKG = "pkg"
    const val EXTRA_MOOD = "mood"
    const val EXTRA_MIN = "min"
    fun cancel(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
}

/** Saves the tapped mood. */
class MoodReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val pkg = i.getStringExtra(Mood.EXTRA_PKG) ?: return
        val done = goAsync()
        Mood.cancel(ctx)
        ctx.graph.scope.launch {
            try {
                ctx.graph.db.moods().insert(MoodEntry(pkg = pkg, ts = System.currentTimeMillis(),
                    mood = i.getIntExtra(Mood.EXTRA_MOOD, 0).coerceIn(-1, 1), sessionMinutes = i.getIntExtra(Mood.EXTRA_MIN, 0)))
            } finally { done.finish() }
        }
    }
}
