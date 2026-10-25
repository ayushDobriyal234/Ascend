package com.ayush.ascend.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayush.ascend.core.LOCK_DAYS
import com.ayush.ascend.core.MIN_LIMIT_MINUTES
import com.ayush.ascend.core.graph
import com.ayush.ascend.data.AppRule
import com.ayush.ascend.data.DayUsage
import com.ayush.ascend.data.InstalledApp
import com.ayush.ascend.data.formatMinutes
import com.ayush.ascend.ui.LocalSnackbar
import com.ayush.ascend.ui.components.AppIcon
import com.ayush.ascend.ui.components.AscendCard
import com.ayush.ascend.ui.components.Chip
import com.ayush.ascend.ui.components.UsageBar
import com.ayush.ascend.ui.message
import com.ayush.ascend.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val MAX_LIMIT = 240

@Composable
fun LimitsScreen() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val rules by g.db.rules().observeAll().collectAsStateWithLifecycle(emptyList())
    val now by produceState(System.currentTimeMillis()) { value = g.clock.now() }
    val today by produceState<DayUsage?>(null) { value = withContext(Dispatchers.Default) { g.usage.today() } }
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }  // pkg, label

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Limits", style = MaterialTheme.typography.headlineMedium)
                Text("Min $MIN_LIMIT_MINUTES min. Each limit locks for $LOCK_DAYS days: stricter anytime, looser never.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (rules.isEmpty()) item {
                AscendCard { Text("No apps yet. Add the one that steals the most time first.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(rules, key = { it.pkg }) { r ->
                val used = today?.minutes(r.pkg) ?: 0
                val days = g.policy.daysLeft(r, now)
                AscendCard(onClick = { editing = r.pkg to r.label }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(r.pkg)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.label, style = MaterialTheme.typography.titleMedium)
                                Text(r.limitMinutes?.let { "${formatMinutes(used)} of ${formatMinutes(it)} today" } ?: "Gate only · ${formatMinutes(used)} today",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (days > 0) Chip("🔒 $days d")
                                if (r.gated) Chip("Gate", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        r.limitMinutes?.let { Spacer(Modifier.height(10.dp)); UsageBar(used / it.toFloat()) }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { picking = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add app") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
            containerColor = Palette.Sun, contentColor = Palette.Forest900,
        )
    }

    if (picking) AppPicker(onDismiss = { picking = false }) { app -> picking = false; editing = app.pkg to app.label }
    editing?.let { (pkg, label) -> RuleEditor(pkg, label, rules.firstOrNull { it.pkg == pkg }, now) { editing = null } }
}

@Composable
fun AppPicker(onDismiss: () -> Unit, onPick: (InstalledApp) -> Unit) {
    val ctx = LocalContext.current
    val apps by produceState(emptyList<InstalledApp>()) { value = withContext(Dispatchers.Default) { ctx.graph.usage.launchableApps() } }
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("Close") } },
        title = { Text("Pick an app") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(apps.filter { it.label.contains(q, true) }, key = { it.pkg }) { a ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(a.pkg, 32.dp); Spacer(Modifier.width(12.dp)); Text(a.label)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun RuleEditor(pkg: String, label: String, existing: AppRule?, now: Long, onDone: () -> Unit) {
    val g = LocalContext.current.graph
    val snackbar = LocalSnackbar.current
    val days = existing?.let { g.policy.daysLeft(it, now) } ?: 0
    val lockedMax = if (days > 0) existing?.limitMinutes else null
    var hasLimit by remember { mutableStateOf(existing?.limitMinutes != null || existing == null) }
    var value by remember { mutableFloatStateOf((existing?.limitMinutes ?: 30).toFloat()) }
    var gated by remember { mutableStateOf(existing?.gated ?: false) }
    val max = (lockedMax ?: MAX_LIMIT).toFloat()
    LaunchedEffect(max) { if (value > max) value = max }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Daily limit", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(hasLimit, { hasLimit = it }, enabled = lockedMax == null)
                }
                if (hasLimit) {
                    Text(formatMinutes(value.roundToInt()), style = MaterialTheme.typography.headlineMedium)
                    Slider(
                        value = value, onValueChange = { value = (it / 5).roundToInt() * 5f },
                        valueRange = MIN_LIMIT_MINUTES.toFloat()..max.coerceAtLeast(MIN_LIMIT_MINUTES.toFloat()),
                    )
                }
                if (lockedMax != null) Text("Locked for $days more days — you can only lower it.", color = Palette.Ember,
                    style = MaterialTheme.typography.bodySmall)
                else if (hasLimit) Text("Saving locks this limit for $LOCK_DAYS days.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Intent gate", style = MaterialTheme.typography.titleMedium)
                        Text("Optional: ask why before every open.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(gated, { gated = it })
                }
            }
        },
        confirmButton = {
            TextButton({
                // app-wide scope: this dialog leaves composition as soon as onDone() runs
                g.scope.launch(Dispatchers.Main) {
                    val limit = if (hasLimit) value.roundToInt() else null
                    val res = if (existing == null) g.policy.addRule(pkg, label, limit, gated)
                    else {
                        val r1 = if (limit != existing.limitMinutes) g.policy.editLimit(existing, limit) else com.ayush.ascend.core.EditResult.Applied
                        val r2 = if (gated != existing.gated) g.policy.setGate(pkg, gated) else com.ayush.ascend.core.EditResult.Applied
                        if (r1 != com.ayush.ascend.core.EditResult.Applied) r1 else r2
                    }
                    res.message()?.let { snackbar.showSnackbar(it) }
                }
                onDone()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
    )
}
