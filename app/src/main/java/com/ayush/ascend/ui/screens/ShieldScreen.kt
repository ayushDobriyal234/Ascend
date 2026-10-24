package com.ayush.ascend.ui.screens

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ayush.ascend.core.Grayscale
import com.ayush.ascend.core.graph
import com.ayush.ascend.data.Feature
import com.ayush.ascend.data.PendingKinds
import com.ayush.ascend.data.formatMinutes
import com.ayush.ascend.service.DnsVpnService
import com.ayush.ascend.ui.LocalSnackbar
import com.ayush.ascend.ui.components.AscendCard
import com.ayush.ascend.ui.components.SectionLabel
import com.ayush.ascend.ui.components.ToggleRow
import com.ayush.ascend.ui.message
import com.ayush.ascend.wallpaper.AscendWallpaperService
import kotlinx.coroutines.launch

@Composable
fun ShieldScreen(nav: NavController) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.prefs.settings.collectAsStateWithLifecycle()
    val pending by g.db.pending().observe().collectAsStateWithLifecycle(emptyList())
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(ctx) == null) scope.launch { g.policy.setFeature(Feature.DNS, true); DnsVpnService.start(ctx) }
    }

    fun pendingFor(f: Feature) = pending.firstOrNull { it.kind == PendingKinds.FEATURE_OFF && it.target == f.key }
        ?.let { "Turns off ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it.applyAt))}" }

    fun toggle(f: Feature, on: Boolean): kotlinx.coroutines.Job = scope.launch {
        if (f == Feature.ADULT && on && !s.on(Feature.DNS)) toggle(Feature.DNS, true)  // 18+ filter rides on the firewall
        if (f == Feature.DNS && on) {
            val consent = VpnService.prepare(ctx)
            if (consent != null) { vpnConsent.launch(consent); return@launch }
            DnsVpnService.start(ctx)
        }
        g.policy.setFeature(f, on).message()?.let { snackbar.showSnackbar(it) }
        if (f == Feature.DNS && !on) DnsVpnService.stop(ctx)
    }

    @Composable
    fun FeatureRow(f: Feature, sub: String) = ToggleRow(f.title, sub, s.on(f), pendingFor(f)) { toggle(f, it) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp, 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Shield", style = MaterialTheme.typography.headlineMedium)
        Text("Turning anything ON is instant. Turning it OFF waits 24 hours — enough for the urge to pass (except the DNS firewall and 18+ filter).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionLabel("Friction")
        AscendCard { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FeatureRow(Feature.GATE, "Asks “why?” before gated apps open. Each reopen within an hour costs more.")
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            FeatureRow(Feature.PREDICTOR, "Learns when you usually slip and adds friction before it happens.")
        } }

        SectionLabel("Reels & Shorts")
        AscendCard { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FeatureRow(Feature.REELS_IG, "Reels gone, DMs and stories stay.")
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            FeatureRow(Feature.REELS_YT, "Shorts gone, search and normal videos stay.")
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            FeatureRow(Feature.REELS_FB, "Reels gone, the rest of Facebook stays.")
            var url by remember(s.rulesUrl) { mutableStateOf(s.rulesUrl) }
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                label = { Text("Rules JSON URL (optional)") })
            TextButton({ scope.launch {
                g.prefs.update { p, k -> p[k.rulesUrl] = url.trim() }
                val v = g.reels.refresh(url.trim())
                snackbar.showSnackbar(if (v != null) "Rules updated to v$v" else "Couldn't load rules from that URL")
            } }) { Text("Save & update rules") }
        } }

        SectionLabel("Grayscale")
        AscendCard { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FeatureRow(Feature.GRAYSCALE, "Black & white after ${formatMinutes(s.grayAfterMin)} of screen time, and from ${s.bedtimeHour}:00 to ${s.wakeHour}:00.")
            Stepper("After", formatMinutes(s.grayAfterMin)) { d ->
                scope.launch { g.prefs.update { p, k -> p[k.grayAfter] = (s.grayAfterMin + d * 15).coerceIn(15, 600) } }
            }
            Stepper("Bedtime", "${s.bedtimeHour}:00") { d -> scope.launch { g.prefs.update { p, k -> p[k.bed] = (s.bedtimeHour + d + 24) % 24 } } }
            Stepper("Wake up", "${s.wakeHour}:00") { d -> scope.launch { g.prefs.update { p, k -> p[k.wake] = (s.wakeHour + d + 24) % 24 } } }
            if (!Grayscale.canWrite(ctx)) Text(
                "One-time setup from a PC:\nadb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        } }

        SectionLabel("Network & notifications")
        AscendCard { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FeatureRow(Feature.DNS, "Blocks your chosen sites in every app and browser, on-device. Turns off instantly if it causes problems.")
            TextButton({ nav.navigate("firewall") }) { Text("Manage blocked sites →") }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            FeatureRow(Feature.ADULT, "Automatically blocks adult sites in every app and browser (Cloudflare family filter). Turns on the DNS firewall. Turns off instantly.")
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            FeatureRow(Feature.BATCH, "Holds notifications from chosen apps and delivers them at ${s.batchSlots.joinToString(", ")}.")
            TextButton({ nav.navigate("inbox") }) { Text("Digest & apps →") }
        } }

        SectionLabel("People & home screen")
        AscendCard { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Accountability partner", style = MaterialTheme.typography.titleMedium)
            Text("A friend gets a Telegram ping when you change a limit or pay to unlock.", style = MaterialTheme.typography.bodySmall)
            TextButton({ nav.navigate("partner") }) { Text("Set up partner & stakes →") }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Minimal launcher", style = MaterialTheme.typography.titleMedium)
            Text("Text-only home screen; distracting apps only appear when you type their full name.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                Modifier.padding(top = 6.dp)) { Text("Choose “Ascend Home” as home app") }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Live wallpaper", style = MaterialTheme.typography.titleMedium)
            Text("Your climb on the home screen. The sky turns dusk as you near your goal, night once you pass it.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton({
                val pick = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(ctx, AscendWallpaperService::class.java))
                runCatching { ctx.startActivity(pick) }
                    .recoverCatching { ctx.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
            }, Modifier.padding(top = 6.dp)) { Text("Set as wallpaper") }
            Text("Some phones only apply live wallpapers to the home screen, not the lock screen.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
}

@Composable
private fun Stepper(label: String, value: String, onStep: (Int) -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton({ onStep(-1) }) { Text("−") }
        Text(value, style = MaterialTheme.typography.labelLarge)
        TextButton({ onStep(1) }) { Text("+") }
    }
}
