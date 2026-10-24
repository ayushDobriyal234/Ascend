package com.ayush.ascend.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ayush.ascend.core.Grayscale
import com.ayush.ascend.core.graph
import com.ayush.ascend.service.GuardService
import com.ayush.ascend.ui.components.AscendCard
import com.ayush.ascend.ui.components.SectionLabel
import com.ayush.ascend.ui.theme.Palette
import kotlinx.coroutines.launch

private data class Step(val title: String, val why: String, val done: Boolean, val required: Boolean, val action: () -> Unit)

@Composable
fun SetupScreen(nav: NavController) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.prefs.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    fun open(action: String, withPkg: Boolean = false) = runCatching {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (withPkg) i.data = Uri.parse("package:${ctx.packageName}")
        ctx.startActivity(i)
    }

    val pm = ctx.getSystemService(PowerManager::class.java)
    val steps = remember(tick) { listOf(
        Step("Usage access", "To measure time per app.", g.usage.hasAccess(), true) { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
        Step("Accessibility: Ascend", "To see which app is open, show the gate, block Reels. You can switch it off any time.",
            GuardService.isEnabled(ctx), true) { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
        Step("Notifications", "For digests and the DNS firewall status.",
            Build.VERSION.SDK_INT < 33 || NotificationManagerCompat.from(ctx).areNotificationsEnabled(), true) {
            if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        Step("Battery: unrestricted", "So your phone doesn't kill Ascend in the background.",
            pm.isIgnoringBatteryOptimizations(ctx.packageName), true) { open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true) },
        Step("Notification access", "Only if you use notification batching.",
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName), false) {
            open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        },
        Step("Grayscale permission", "Only for auto grayscale. One adb command from a PC (see Shield).",
            Grayscale.canWrite(ctx), false) {},
    ) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp, 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Set up Ascend", style = MaterialTheme.typography.headlineMedium)
        Text("Everything runs on your phone. Each permission says what it's for.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        steps.forEach { st ->
            AscendCard(onClick = if (st.done) null else st.action) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (st.done) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
                        tint = if (st.done) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(st.title + if (st.required) "" else "  · optional", style = MaterialTheme.typography.titleMedium)
                        Text(st.why, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        SectionLabel("You")
        var name by remember(s.myName) { mutableStateOf(s.myName) }
        var goal by remember(s.dailyGoalMin) { mutableStateOf((s.dailyGoalMin / 60.0).toString()) }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Your name (used in partner messages)") }, singleLine = true)
        OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth(), label = { Text("Daily screen-time goal (hours)") }, singleLine = true)

        Spacer(Modifier.height(8.dp))
        val ready = steps.filter { it.required }.all { it.done }
        Button(
            onClick = {
                scope.launch {
                    g.prefs.update { p, k ->
                        p[k.me] = name.trim()
                        goal.toDoubleOrNull()?.let { p[k.goal] = (it * 60).toInt().coerceIn(30, 900) }
                        p[k.onboarded] = true
                    }
                    nav.navigate("home") { popUpTo("home") { inclusive = true } }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Sun, contentColor = Palette.Forest900),
        ) { Text(if (ready) "Start climbing" else "Save & continue anyway") }
        if (!ready) TextButton({}) { Text("Limits aren't enforced until the required steps are done.", style = MaterialTheme.typography.bodySmall) }
    }
}
