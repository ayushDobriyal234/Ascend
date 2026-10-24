package com.ayush.ascend.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ayush.ascend.R
import com.ayush.ascend.core.KNOWN_DISTRACTING
import com.ayush.ascend.core.Progress
import com.ayush.ascend.core.TimeCost
import com.ayush.ascend.core.graph
import com.ayush.ascend.data.DayUsage
import com.ayush.ascend.data.Feature
import com.ayush.ascend.data.PendingKinds
import com.ayush.ascend.data.formatMinutes
import com.ayush.ascend.service.GuardService
import com.ayush.ascend.ui.components.AppIcon
import com.ayush.ascend.ui.components.AscendCard
import com.ayush.ascend.ui.components.Chip
import com.ayush.ascend.ui.components.CoinAmount
import com.ayush.ascend.ui.components.MountainClimb
import com.ayush.ascend.ui.components.SectionLabel
import com.ayush.ascend.ui.components.StatTile
import com.ayush.ascend.ui.components.UsageBar
import com.ayush.ascend.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(nav: NavController) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val settings by g.prefs.settings.collectAsStateWithLifecycle()
    val rules by g.db.rules().observeAll().collectAsStateWithLifecycle(emptyList())
    val days by g.db.days().observeRecent(60).collectAsStateWithLifecycle(emptyList())
    val pending by g.db.pending().observe().collectAsStateWithLifecycle(emptyList())
    val risk by g.predictor.risk.collectAsStateWithLifecycle()
    var today by remember { mutableStateOf<DayUsage?>(null) }
    var month by remember { mutableStateOf<DayUsage?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { Progress.backfill(g) }
        month = withContext(Dispatchers.Default) {
            g.usage.between(g.usage.startOfDay(java.time.LocalDate.now().withDayOfMonth(1)), System.currentTimeMillis())
        }
        while (true) {
            today = withContext(Dispatchers.Default) { g.usage.today(5_000) }
            delay(30_000)
        }
    }

    val streak = Progress.streak(days)
    val u = today
    val guardOn = GuardService.isEnabled(ctx)

    LazyColumn(
        Modifier.fillMaxWidth().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ascend_logo), null, Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(10.dp))
                Text("Ascend", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton({ nav.navigate("shop") }) { CoinAmount("${settings.acPoints}", color = MaterialTheme.colorScheme.primary, coin = 22.dp) }
                IconButton({ nav.navigate("setup") }) { Icon(Icons.Outlined.Settings, "Setup") }
            }
        }
        if (!guardOn || !g.usage.hasAccess()) item {
            AscendCard(onClick = { nav.navigate("setup") }) {
                Column {
                    Text("Finish setup", style = MaterialTheme.typography.titleMedium, color = Palette.Ember)
                    Text("Some permissions are missing, so limits aren't enforced yet. Tap to fix.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(1.55f).clip(RoundedCornerShape(26.dp))) {
                MountainClimb(streak / Progress.SUMMIT_DAYS.toFloat(), Modifier.fillMaxWidth().aspectRatio(1.55f))
                // kept to the left half: the peak's slope starts around 45 % of the width
                Column(Modifier.padding(18.dp).fillMaxWidth(0.5f)) {
                    Text("${Progress.altitude(streak)} m", style = MaterialTheme.typography.displayMedium, color = Palette.Forest900)
                    Text("Day $streak of ${Progress.SUMMIT_DAYS}", style = MaterialTheme.typography.labelLarge, color = Palette.Forest700)
                    Text("Summit at ${Progress.altitude(Progress.SUMMIT_DAYS)} m", style = MaterialTheme.typography.labelMedium, color = Palette.Forest700)
                }
            }
        }
        item {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(u?.let { formatMinutes(it.totalMinutes) } ?: "–", "of ${formatMinutes(settings.dailyGoalMin)} goal", Modifier.weight(1f).fillMaxHeight())
                StatTile(u?.unlocks?.toString() ?: "–", "unlocks", Modifier.weight(1f).fillMaxHeight())
                StatTile(u?.let { "%.1f".format(it.pickupsPerHour) } ?: "–", "pickups / hr", Modifier.weight(1f).fillMaxHeight())
            }
        }
        month?.let { m ->
            // top distracting app this month: a rule or a known infinite feed
            val top = m.perAppMs.filterKeys { k -> k in KNOWN_DISTRACTING || rules.any { it.pkg == k } }.maxByOrNull { it.value }
            val topMin = (top?.value ?: 0L).div(60_000).toInt()
            val line = when {
                top != null && TimeCost.line(topMin).isNotEmpty() -> "This month: ${formatMinutes(topMin)} on ${g.usage.label(top.key)} = ${TimeCost.line(topMin)}"
                TimeCost.line(m.totalMinutes).isNotEmpty() -> "This month: ${formatMinutes(m.totalMinutes)} on your phone = ${TimeCost.line(m.totalMinutes)}"
                else -> null
            }
            if (line != null) item {
                AscendCard { Column {
                    Text(line, style = MaterialTheme.typography.titleMedium)
                    if (top != null) Text("All apps: ${formatMinutes(m.totalMinutes)} = ${TimeCost.line(m.totalMinutes, 3)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
        }
        if (settings.on(Feature.PREDICTOR) && risk >= 0.5) item {
            AscendCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip("${(risk * 100).toInt()}% risk", Palette.Ember.copy(alpha = 0.18f), Palette.Ember)
                    Spacer(Modifier.width(10.dp))
                    Text("This is usually when you slip. Extra friction is on for 20 min.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (pending.isNotEmpty()) {
            item { SectionLabel("Waiting 24 h") }
            items(pending, key = { it.id }) { p ->
                AscendCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(describe(p.kind, p.target, g), style = MaterialTheme.typography.titleMedium)
                            Text("Applies ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.applyAt))}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton({ scope.launch { g.policy.cancelPending(p.id) } }) { Text("Cancel") }
                    }
                }
            }
        }
        item { SectionLabel("Today") }
        val ruleMap = rules.associateBy { it.pkg }
        val top = u?.perAppMs?.entries?.sortedByDescending { it.value }?.take(10).orEmpty()
        if (top.isEmpty()) item { Text("No usage yet today.", style = MaterialTheme.typography.bodyMedium) }
        items(top, key = { it.key }) { (pkg, ms) ->
            val min = (ms / 60_000).toInt()
            val rule = ruleMap[pkg]
            AscendCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(g.usage.label(pkg), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(rule?.limitMinutes?.let { "${formatMinutes(min)} / ${formatMinutes(it)}" } ?: formatMinutes(min),
                                style = MaterialTheme.typography.labelMedium)
                        }
                        rule?.limitMinutes?.let { Spacer(Modifier.height(6.dp)); UsageBar(min / it.toFloat()) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun describe(kind: String, target: String, g: com.ayush.ascend.core.Graph): String = when (kind) {
    PendingKinds.GATE_OFF -> "Gate off for ${g.usage.label(target)}"
    PendingKinds.FEATURE_OFF -> "${Feature.entries.firstOrNull { it.key == target }?.title ?: target} off"
    PendingKinds.DOMAIN_REMOVE -> "Unblock $target"
    PendingKinds.BATCH_APP_REMOVE -> "Stop batching ${g.usage.label(target)}"
    else -> target
}
