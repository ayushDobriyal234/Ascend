package com.ayush.ascend.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ayush.ascend.core.LOCK_DAYS
import com.ayush.ascend.core.MIN_LIMIT_MINUTES
import com.ayush.ascend.core.graph
import com.ayush.ascend.data.Feature
import com.ayush.ascend.data.formatMinutes
import com.ayush.ascend.ui.LocalSnackbar
import com.ayush.ascend.ui.components.AscendCard
import com.ayush.ascend.ui.components.SectionLabel
import com.ayush.ascend.ui.components.ToggleRow
import com.ayush.ascend.ui.message
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.prefs.settings.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)

    SubScreen("Settings", nav) {
        SectionLabel("Appearance")
        AscendCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                    FilterChip(s.themeMode == mode, { scope.launch { g.prefs.update { p, k -> p[k.theme] = mode } } }, { Text(label) })
                }
            }
        }

        SectionLabel("Friction")
        AscendCard { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleRow(Feature.GATE.title, "Optional. Asks “why?” before gated apps open. Pick which apps in Limits. Turns off instantly.",
                s.on(Feature.GATE)) { on -> scope.launch { g.policy.setFeature(Feature.GATE, on).message()?.let { snackbar.showSnackbar(it) } } }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Reels & Shorts per day", style = MaterialTheme.typography.titleMedium)
            Text("After this many, Reels and Shorts are blocked until midnight. None = always blocked.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Stepper("Allowed", if (s.reelLimit == 0) "None" else "${s.reelsToday}/${s.reelLimit}") { d ->
                scope.launch { g.prefs.update { p, k -> p[k.reelLimit] = (s.reelLimit + d * 10).coerceIn(0, 500) } }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Stepper("Daily screen-time goal", formatMinutes(s.dailyGoalMin)) { d ->
                scope.launch { g.prefs.update { p, k -> p[k.goal] = (s.dailyGoalMin + d * 15).coerceIn(30, 720) } }
            }
        } }

        SectionLabel("Permissions")
        AscendCard {
            TextButton({ nav.navigate("setup") }) { Text("Setup & permissions →") }
        }

        SectionLabel("Good to know")
        AscendCard { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "App limits are at least $MIN_LIMIT_MINUTES min and lock for $LOCK_DAYS days. You can make them stricter any time, never looser.",
                "Turning a protection off waits 24 h. The intent gate, grayscale, DNS firewall and 18+ filter switch off instantly.",
                "Changing the phone's date doesn't skip a lock — Ascend checks the real time online.",
                "Accessibility must stay on for limits, the gate and Reels blocking to work.",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        } }

        SectionLabel("About")
        AscendCard { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Ascend ${info.versionName}", style = MaterialTheme.typography.titleMedium)
            Text("Build ${PackageInfoCompat.getLongVersionCode(info)} · ${ctx.packageName}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
}
