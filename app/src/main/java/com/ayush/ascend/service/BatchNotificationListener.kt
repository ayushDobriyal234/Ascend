package com.ayush.ascend.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ayush.ascend.core.graph
import com.ayush.ascend.data.Feature
import com.ayush.ascend.data.HeldNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** #7 Holds notifications from the apps you chose and releases them as a digest at set times. */
class BatchNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var apps: Set<String> = emptySet()

    override fun onListenerConnected() {
        scope.launch { graph.db.notifs().observeApps().collectLatest { l -> apps = l.map { it.pkg }.toSet() } }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in apps || sbn.isOngoing) return
        val s = graph.prefs.settings.value
        if (!s.on(Feature.BATCH)) return
        val n = sbn.notification
        if (n.category in setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM,
                Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_TRANSPORT)) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) { cancelNotification(sbn.key); return }
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val hay = "$title $text".lowercase()
        if (s.batchKeywords.any { it.isNotBlank() && hay.contains(it) }) return  // let urgent ones through

        cancelNotification(sbn.key)
        scope.launch {
            graph.db.notifs().insert(
                HeldNotification(pkg = sbn.packageName, appLabel = graph.usage.label(sbn.packageName),
                    title = title, text = text, postedAt = sbn.postTime)
            )
        }
    }
}
