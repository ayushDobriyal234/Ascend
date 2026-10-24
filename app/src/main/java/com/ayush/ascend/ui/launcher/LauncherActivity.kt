package com.ayush.ascend.ui.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayush.ascend.core.KNOWN_DISTRACTING
import com.ayush.ascend.core.graph
import com.ayush.ascend.data.InstalledApp
import com.ayush.ascend.data.formatMinutes
import com.ayush.ascend.ui.MainActivity
import com.ayush.ascend.ui.theme.AscendTheme
import com.ayush.ascend.ui.theme.Montserrat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * #11 Minimal launcher. Text only, no icons, no badges.
 * Pinned apps show on the home page; distracting apps never appear until you type ≥ 4 letters of their name.
 * Long-press any app to pin/unpin.
 */
class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        setContent { AscendTheme { BackHandler {}; Home() } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Home() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.prefs.settings.collectAsStateWithLifecycle()
    val rules by g.db.rules().observeAll().collectAsStateWithLifecycle(emptyList())
    val apps by produceState(emptyList<InstalledApp>()) { value = withContext(Dispatchers.Default) { g.usage.launchableApps() } }
    val time by produceState(LocalTime.now()) { while (true) { value = LocalTime.now(); delay(15_000) } }
    val today by produceState(0) { while (true) { value = withContext(Dispatchers.Default) { g.usage.today().totalMinutes }; delay(60_000) } }
    var q by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val distracting = rules.map { it.pkg }.toSet() + KNOWN_DISTRACTING

    val shown = if (q.isBlank()) apps.filter { it.pkg in s.pinned }
    else apps.filter { it.label.contains(q.trim(), ignoreCase = true) && (it.pkg !in distracting || q.trim().length >= 4) }

    fun togglePin(pkg: String) = scope.launch {
        g.prefs.update { p, k -> p[k.pinned] = if (pkg in s.pinned) s.pinned - pkg else s.pinned + pkg }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text(time.format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.displayLarge, fontFamily = Montserrat)
        Text("${formatMinutes(today)} on screen today · goal ${formatMinutes(s.dailyGoalMin)}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.pkg }) { a ->
                Text(
                    a.label + if (a.pkg in s.pinned && q.isNotBlank()) "  ·" else "",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { g.usage.launch(a.pkg); q = "" }, onLongClick = { togglePin(a.pkg) })
                        .padding(vertical = 10.dp),
                )
            }
            if (q.isBlank() && shown.isEmpty()) item {
                Text("Search below, long-press an app to pin it here.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Type an app name") }, singleLine = true)
        TextButton({ ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
            Text("Open Ascend")
        }
    }
    LaunchedEffect(Unit) { g.usage.invalidate() }
}
