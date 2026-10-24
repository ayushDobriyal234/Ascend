package com.ayush.ascend.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayush.ascend.core.Chest
import com.ayush.ascend.core.TaskState
import com.ayush.ascend.core.Wallet
import com.ayush.ascend.core.graph
import com.ayush.ascend.ui.LocalSnackbar
import com.ayush.ascend.ui.components.AscendCard
import com.ayush.ascend.ui.components.Chip
import com.ayush.ascend.ui.components.Coin
import com.ayush.ascend.ui.components.CoinAmount
import com.ayush.ascend.ui.components.SectionLabel
import com.ayush.ascend.ui.components.StatTile
import com.ayush.ascend.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun ShopScreen() {
    val g = LocalContext.current.graph
    val settings by g.prefs.settings.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf(emptyList<TaskState>()) }
    var chestReady by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<Chest?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var stepPerm by remember { mutableStateOf(g.wallet.hasStepPermission()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { stepPerm = it; reload++ }

    LaunchedEffect(reload) { tasks = g.wallet.tasks(); chestReady = g.wallet.chestReady() }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Shop", style = MaterialTheme.typography.headlineMedium)
            Text("Earn coins with real-life tasks, trade them for bonus minutes. Bonus minutes only work once an app's daily limit is used up.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${settings.acPoints}", "coins", Modifier.weight(1f), leading = { Coin(22.dp) })
                StatTile("${settings.bonusMinutes} min", "bonus minutes", Modifier.weight(1f))
            }
        }
        if (chestReady) item {
            AscendCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎁 Today's chest is waiting", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Button({ scope.launch { opened = g.wallet.openChest(); reload++ } }, colors = sunButton()) { Text("Open") }
                }
            }
        }

        item { SectionLabel("Daily tasks") }
        if (!stepPerm && Build.VERSION.SDK_INT >= 29) item {
            AscendCard(onClick = { permLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION) }) {
                Text("Allow Physical activity so Ascend can count your steps. Tap to allow.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(tasks, key = { it.id }) { t ->
            AscendCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.titleMedium)
                            Text(if (t.target > 1) "${t.progress.coerceAtMost(t.target)} / ${t.target}" else if (t.done) "Done" else "Not yet",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(10.dp))
                        when {
                            t.claimed -> Chip("Claimed")
                            t.done -> Button({ scope.launch { if (g.wallet.claim(t)) snackbar.showSnackbar("+${t.reward} coins"); reload++ } },
                                colors = sunButton()) { CoinAmount("+${t.reward}", MaterialTheme.typography.labelLarge, coin = 16.dp) }
                            else -> CoinAmount("+${t.reward}", MaterialTheme.typography.labelLarge, coin = 16.dp)
                        }
                    }
                    if (t.target > 1) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { t.progress / t.target.toFloat() }, Modifier.fillMaxWidth())
                    }
                }
            }
        }

        item { SectionLabel("Buy bonus minutes") }
        items(Wallet.OFFERS, key = { it.minutes }) { o ->
            AscendCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${o.minutes} min", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Button(
                        { scope.launch { snackbar.showSnackbar(if (g.wallet.buy(o)) "+${o.minutes} bonus min" else "Not enough coins") } },
                        enabled = settings.acPoints >= o.cost, colors = sunButton(),
                    ) { CoinAmount("${o.cost}", MaterialTheme.typography.labelLarge, coin = 16.dp) }
                }
            }
        }
    }
    opened?.let { ChestResult(it) { opened = null } }
}

@Composable
private fun sunButton() = ButtonDefaults.buttonColors(containerColor = Palette.Sun, contentColor = Palette.Forest900)

@Composable
fun ChestResult(c: Chest, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("Nice") } },
        title = { Text("🎁 Daily chest", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CoinAmount("+${c.points}", MaterialTheme.typography.displaySmall, coin = 36.dp)
                if (c.minutes > 0) Text("Rare drop! +${c.minutes} bonus minutes", color = Palette.Ember, style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

/** Shown once a day on first open; tapping Open rolls the chest. */
@Composable
fun DailyChestPrompt(onDone: () -> Unit) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<Chest?>(null) }
    val r = result
    if (r != null) { ChestResult(r, onDone); return }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("🎁 Your daily chest", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
        text = { Text("Open it for coins. Rarely, it holds bonus minutes.", textAlign = TextAlign.Center) },
        confirmButton = {
            Button({ scope.launch { g.wallet.openChest()?.let { result = it } ?: onDone() } }, colors = sunButton()) { Text("Open") }
        },
        dismissButton = { TextButton(onDone) { Text("Later") } },
    )
}
