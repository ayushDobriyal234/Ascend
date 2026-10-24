package com.ayush.ascend.work

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ayush.ascend.AscendApp
import com.ayush.ascend.R
import com.ayush.ascend.core.Progress
import com.ayush.ascend.core.Report
import com.ayush.ascend.core.graph
import com.ayush.ascend.data.Feature
import com.ayush.ascend.service.DnsVpnService
import com.ayush.ascend.ui.MainActivity
import com.ayush.ascend.widget.AscendWidget
import kotlinx.coroutines.runBlocking
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

object Workers {
    fun scheduleAll(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork("maintenance", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(3, TimeUnit.HOURS).build())
        wm.enqueueUniquePeriodicWork("train", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TrainWorker>(12, TimeUnit.HOURS).build())
        wm.enqueueUniquePeriodicWork("widget", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetWorker>(30, TimeUnit.MINUTES).build())
        BatchScheduler.scheduleNext(ctx)
        WeeklyReportWorker.scheduleNext(ctx, ExistingWorkPolicy.KEEP)
    }
}

/** Applies due 24 h changes, backfills day stats (streak), refreshes Reels rules. */
class MaintenanceWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        g.policy.applyDue()
        g.clock.verifiedNow()
        Progress.backfill(g)
        g.reels.refresh(g.prefs.current().rulesUrl)
        g.db.notifs().prune(System.currentTimeMillis() - 7L * 24 * 3_600_000)
        return Result.success()
    }
}

class TrainWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        applicationContext.graph.predictor.labelAndTrain()
        return Result.success()
    }
}

class WidgetWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        AscendWidget().updateAll(applicationContext)
        return Result.success()
    }
}

/** #7 Delivers held notifications as one digest at each slot (default 13:00, 18:00, 21:00). */
object BatchScheduler {
    fun scheduleNext(ctx: Context) {
        val slots = runBlocking { ctx.graph.prefs.current().batchSlots }
            .mapNotNull { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }
            .ifEmpty { listOf(LocalTime.of(13, 0), LocalTime.of(18, 0), LocalTime.of(21, 0)) }
            .sorted()
        val now = LocalDateTime.now()
        val next = slots.map { now.toLocalDate().atTime(it) }.firstOrNull { it.isAfter(now) }
            ?: now.toLocalDate().plusDays(1).atTime(slots.first())
        val delay = Duration.between(now, next).toMillis()
        WorkManager.getInstance(ctx).enqueueUniqueWork("batch_delivery", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BatchDeliveryWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
    }
}

class BatchDeliveryWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val held = ctx.graph.db.notifs().undelivered()
        if (held.isNotEmpty()) {
            val apps = held.map { it.appLabel }.distinct()
            val style = NotificationCompat.InboxStyle()
            held.take(6).forEach { style.addLine("${it.appLabel}: ${it.title} — ${it.text}".take(90)) }
            if (held.size > 6) style.setSummaryText("+${held.size - 6} more")
            val open = PendingIntent.getActivity(ctx, 7,
                Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "inbox")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(ctx, AscendApp.CH_BATCH)
                .setSmallIcon(R.drawable.ic_monochrome)
                .setContentTitle("${held.size} notifications from ${apps.size} apps")
                .setContentText(apps.take(4).joinToString(" · "))
                .setStyle(style)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(4201, n) }
            ctx.graph.db.notifs().markAllDelivered()
        }
        BatchScheduler.scheduleNext(ctx)
        return Result.success()
    }
}

/** #16 Sunday 7 PM: grades the last 7 days vs the 7 before, stores it, notifies "Your week: B+ 📈". */
class WeeklyReportWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val r = Report.build(ctx.graph)
        ctx.graph.db.reports().upsert(r)
        val open = PendingIntent.getActivity(ctx, 8,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "report")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, AscendApp.CH_BATCH)
            .setSmallIcon(R.drawable.ic_monochrome)
            .setContentTitle("Your week: ${r.grade} ${if (r.trendPct <= 0) "📈" else "📉"}")
            .setContentText("${com.ayush.ascend.data.formatMinutes(r.avgMinutes)}/day · ${Report.arrow(r.trendPct)} vs last week · tap for your report card")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(4202, n) }
        scheduleNext(ctx, ExistingWorkPolicy.REPLACE)
        return Result.success()
    }

    companion object {
        fun scheduleNext(ctx: Context, policy: ExistingWorkPolicy) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(19, 0)
            if (!next.isAfter(now)) next = next.plusWeeks(1)
            WorkManager.getInstance(ctx).enqueueUniqueWork("weekly_report", policy,
                OneTimeWorkRequestBuilder<WeeklyReportWorker>()
                    .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS).build())
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Workers.scheduleAll(ctx)
        val g = ctx.graph
        val s = runBlocking { g.prefs.current() }
        if (s.on(Feature.DNS) && VpnService.prepare(ctx) == null) DnsVpnService.start(ctx)
    }
}
