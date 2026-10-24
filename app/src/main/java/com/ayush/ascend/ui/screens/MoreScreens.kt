package com.ayush.ascend.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ayush.ascend.core.Partner
import com.ayush.ascend.core.graph
import com.ayush.ascend.data.PendingKinds
import com.ayush.ascend.ui.LocalSnackbar
import com.ayush.ascend.ui.components.AppIcon
import com.ayush.ascend.ui.components.AscendCard
import com.ayush.ascend.ui.components.SectionLabel
import com.ayush.ascend.ui.message
import com.ayush.ascend.ui.theme.Palette
import com.ayush.ascend.work.BatchScheduler
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SubScreen(title: String, nav: NavController, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp, 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        content()
    }
}

// ---------------- #7 Inbox / batching ----------------

@Composable
fun InboxScreen(nav: NavController) {
    val g = LocalContext.current.graph
    val ctx = LocalContext.current
    val s by g.prefs.settings.collectAsStateWithLifecycle()
    val since = remember { System.currentTimeMillis() - 2L * 24 * 3_600_000 }
    val held by g.db.notifs().observeSince(since).collectAsStateWithLifecycle(emptyList())
    val apps by g.db.notifs().observeApps().collectAsStateWithLifecycle(emptyList())
    val pending by g.db.pending().observe().collectAsStateWithLifecycle(emptyList())
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }

    SubScreen("Digest", nav) {
        SectionLabel("Batched apps")
        AscendCard { Column {
            if (apps.isEmpty()) Text("No apps batched yet.", style = MaterialTheme.typography.bodySmall)
            apps.forEach { a ->
                val leaving = pending.any { it.kind == PendingKinds.BATCH_APP_REMOVE && it.target == a.pkg }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(a.pkg, 30.dp); Spacer(Modifier.width(10.dp))
                    Text(a.label, Modifier.weight(1f))
                    if (leaving) Text("leaving in 24 h", style = MaterialTheme.typography.labelSmall, color = Palette.Ember)
                    else TextButton({ scope.launch { g.policy.removeBatchApp(a.pkg).message()?.let { snackbar.showSnackbar(it) } } }) { Text("Remove") }
                }
            }
            TextButton({ picking = true }) { Text("+ Add app") }
        } }

        SectionLabel("Delivery times")
        AscendCard { Column {
            var slots by remember(s.batchSlots) { mutableStateOf(s.batchSlots.joinToString(", ")) }
            var keywords by remember(s.batchKeywords) { mutableStateOf(s.batchKeywords.joinToString(", ")) }
            OutlinedTextField(slots, { slots = it }, Modifier.fillMaxWidth(), label = { Text("Times (24h, comma separated)") }, singleLine = true)
            OutlinedTextField(keywords, { keywords = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("Always let through if it contains") }, singleLine = true)
            TextButton({ scope.launch {
                g.prefs.update { p, k -> p[k.slots] = slots; p[k.keywords] = keywords }
                BatchScheduler.scheduleNext(ctx)
                snackbar.showSnackbar("Saved")
            } }) { Text("Save") }
        } }

        SectionLabel("Last 48 hours")
        if (held.isEmpty()) Text("Nothing held.", style = MaterialTheme.typography.bodySmall)
        held.groupBy { it.pkg }.forEach { (pkg, list) ->
            AscendCard(onClick = { g.usage.launch(pkg) }) { Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg, 28.dp); Spacer(Modifier.width(10.dp))
                    Text("${list.first().appLabel} · ${list.size}", style = MaterialTheme.typography.titleMedium)
                }
                list.take(5).forEach { n ->
                    Text("${n.title}: ${n.text}".take(120), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(list.first().postedAt)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }
    if (picking) AppPicker({ picking = false }) { a -> picking = false; scope.launch { g.policy.addBatchApp(a.pkg, a.label) } }
}

// ---------------- #6 Firewall ----------------

private val PRESETS = mapOf(
    "Social" to listOf("instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net", "x.com", "twitter.com", "reddit.com", "snapchat.com"),
    "Video" to listOf("youtube.com", "youtu.be", "googlevideo.com", "netflix.com", "primevideo.com", "hotstar.com"),
    "News" to listOf("inshorts.com", "ndtv.com", "timesofindia.indiatimes.com"),
)

@Composable
fun FirewallScreen(nav: NavController) {
    val g = LocalContext.current.graph
    val domains by g.db.domains().observe().collectAsStateWithLifecycle(emptyList())
    val pending by g.db.pending().observe().collectAsStateWithLifecycle(emptyList())
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

    SubScreen("Blocked sites", nav) {
        Text("Applies to ALL apps and browsers. Blocking instagram.com also blocks the Instagram app.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("example.com") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button({ scope.launch { g.policy.addDomain(input); input = "" } },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Sun, contentColor = Palette.Forest900)) { Text("Block") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { (name, list) ->
                OutlinedButton({ scope.launch { list.forEach { g.policy.addDomain(it) } } }) { Text("+ $name") }
            }
        }
        SectionLabel("${domains.size} blocked")
        domains.forEach { d ->
            val leaving = pending.any { it.kind == PendingKinds.DOMAIN_REMOVE && it.target == d.domain }
            AscendCard { Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.domain, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (leaving) Text("unblocks in 24 h", style = MaterialTheme.typography.labelSmall, color = Palette.Ember)
                else TextButton({ scope.launch { g.policy.removeDomain(d.domain).message()?.let { snackbar.showSnackbar(it) } } }) { Text("Unblock") }
            } }
        }
        Text("Tip: in Settings → Network → Private DNS, choose “Off” or “Automatic”, otherwise Android bypasses the firewall.",
            style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- #10 Partner & stakes ----------------

@Composable
fun PartnerScreen(nav: NavController) {
    val g = LocalContext.current.graph
    val s by g.prefs.settings.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var token by remember(s.tgToken) { mutableStateOf(s.tgToken) }
    var chat by remember(s.tgChat) { mutableStateOf(s.tgChat) }
    var partner by remember(s.partnerName) { mutableStateOf(s.partnerName) }
    var link by remember(s.stakeLink) { mutableStateOf(s.stakeLink) }
    var amount by remember(s.stakeAmount) { mutableStateOf(s.stakeAmount.toString()) }

    SubScreen("Partner & stakes", nav) {
        SectionLabel("Telegram")
        AscendCard { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1. Create a bot with @BotFather and copy its token.\n2. Your friend sends the bot any message.\n3. Open api.telegram.org/bot<TOKEN>/getUpdates and copy their chat id.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(partner, { partner = it }, Modifier.fillMaxWidth(), label = { Text("Partner's name") }, singleLine = true)
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Bot token") }, singleLine = true)
            OutlinedTextField(chat, { chat = it }, Modifier.fillMaxWidth(), label = { Text("Partner chat id") }, singleLine = true)
        } }
        SectionLabel("Stakes jar (optional)")
        AscendCard { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("When a limit is hit you can pay into a Razorpay payment link for 15 more minutes. Honour system — your partner gets told and can check the payment.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("Razorpay payment link (rzp.io/…)") }, singleLine = true)
            OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Amount (₹)") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        } }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ scope.launch {
                g.prefs.update { p, k ->
                    p[k.tgToken] = token.trim(); p[k.tgChat] = chat.trim(); p[k.partner] = partner.trim()
                    p[k.stakeLink] = link.trim(); p[k.stakeAmount] = amount.toIntOrNull()?.coerceAtLeast(1) ?: 50
                }
                snackbar.showSnackbar("Saved")
            } }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Sun, contentColor = Palette.Forest900)) { Text("Save") }
            OutlinedButton({ scope.launch {
                val ok = g.partner.send("👋 {me} added you as their Ascend accountability partner.", Partner.Event.TEST)
                snackbar.showSnackbar(if (ok) "Test message sent" else "Couldn't send — check token & chat id (save first)")
            } }) { Text("Send test") }
        }
    }
}
