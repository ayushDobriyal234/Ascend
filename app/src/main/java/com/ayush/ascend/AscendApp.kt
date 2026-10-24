package com.ayush.ascend

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.ayush.ascend.core.Graph
import com.ayush.ascend.work.Workers

class AscendApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_BATCH, "Notification digests", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_GUARD, "Guard status", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_ALERT, "Alerts", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_MOOD, "Mood check-ins", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
        Workers.scheduleAll(this)
    }

    companion object {
        const val CH_BATCH = "batch"
        const val CH_GUARD = "guard"
        const val CH_ALERT = "alert"
        const val CH_MOOD = "mood"
    }
}
