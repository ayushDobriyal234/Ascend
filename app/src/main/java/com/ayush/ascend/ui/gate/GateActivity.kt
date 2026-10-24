package com.ayush.ascend.ui.gate

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ayush.ascend.R
import com.ayush.ascend.core.FrictionPlan
import com.ayush.ascend.core.Sessions
import com.ayush.ascend.core.graph
import com.ayush.ascend.data.OpenEvent
import com.ayush.ascend.ui.theme.AscendTheme
import com.ayush.ascend.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** #2 Intent gate + #4 escalating friction. */
class GateActivity : ComponentActivity() {

    companion object {
        private const val PKG = "pkg"; private const val LABEL = "label"; private const val RISK = "risk"
        fun intent(ctx: Context, pkg: String, label: String, highRisk: Boolean) =
            Intent(ctx, GateActivity::class.java)
                .putExtra(PKG, pkg).putExtra(LABEL, label).putExtra(RISK, highRisk)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun render() {
        val pkg = intent.getStringExtra(PKG) ?: return finish()
        val label = intent.getStringExtra(LABEL) ?: pkg
        val risk = intent.getBooleanExtra(RISK, false)
        setContent {
            AscendTheme {
                BackHandler { goHome() }
                Gate(pkg, label, risk, onLeave = ::goHome, onEnter = { reason, minutes, level ->
                    Sessions.grant(pkg, minutes)
                    val g = graph
                    g.scope.launch {
                        g.db.opens().insert(OpenEvent(pkg = pkg, ts = System.currentTimeMillis(), type = "gate",
                            reason = reason, minutes = minutes, frictionLevel = level))
                    }
                    g.usage.launch(pkg)
                    finish()
                })
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

@Composable
private fun Gate(pkg: String, label: String, highRisk: Boolean, onLeave: () -> Unit, onEnter: (String, Int, Int) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val g = ctx.graph
    val plan by produceState<FrictionPlan?>(null, pkg) { value = g.friction.plan(pkg, label, highRisk) }
    val remainingLimit by produceState<Int?>(null, pkg) {
        val rule = g.db.rules().get(pkg)
        val used = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { g.usage.today(0).minutes(pkg) }
        value = rule?.limitMinutes?.let { (it - used).coerceAtLeast(0) }
    }
    var reason by remember { mutableStateOf("") }
    var minutes by remember { mutableIntStateOf(5) }
    var sentence by remember { mutableStateOf("") }
    var math by remember { mutableStateOf("") }
    var waitLeft by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(plan) {
        val p = plan ?: return@LaunchedEffect
        waitLeft = p.waitSeconds
        while (waitLeft > 0) { delay(1_000); waitLeft-- }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Palette.Ink, unfocusedTextColor = Palette.Ink,
        focusedBorderColor = Palette.Forest700, unfocusedBorderColor = Palette.Sage, cursorColor = Palette.Forest700,
        focusedLabelColor = Palette.Forest700, unfocusedLabelColor = Palette.Forest500,
    )

    Column(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White).systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Image(painterResource(R.drawable.ascend_logo), null, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
        Text("Why are you opening $label?", style = MaterialTheme.typography.headlineMedium, color = Palette.Forest900)
        val p = plan
        if (p == null) return@Column
        if (p.level > 0 || highRisk) {
            Text(
                buildString {
                    if (p.level > 0) append("Opened ${p.level - (if (highRisk) 1 else 0)}× in the last hour. ")
                    if (highRisk) append("This is usually when you slip.")
                }.trim(),
                style = MaterialTheme.typography.bodyMedium, color = Palette.SunSoft,
            )
        }
        OutlinedTextField(reason, { reason = it }, Modifier.fillMaxWidth(), label = { Text("I'm opening it to…") },
            colors = fieldColors, minLines = 2)

        Text("For how long?", style = MaterialTheme.typography.titleMedium, color = Palette.Forest900)
        val cap = remainingLimit
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(5, 10, 15).forEach { m ->
                val allowed = cap == null || m <= cap
                FilterChip(
                    selected = minutes == m, onClick = { minutes = m }, enabled = allowed,
                    label = { Text("$m min") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Palette.Sun, selectedLabelColor = Palette.Forest900, labelColor = Palette.Forest900,
                    ),
                )
            }
        }
        if (cap != null) Text("$cap min left in today's limit.", style = MaterialTheme.typography.bodySmall, color = Palette.Forest500)

        p.sentence?.let { s ->
            Text("Type this exactly:", style = MaterialTheme.typography.titleMedium, color = Palette.Forest900)
            Text("“$s”", style = MaterialTheme.typography.bodyLarge, color = Palette.SunSoft)
            OutlinedTextField(sentence, { sentence = it }, Modifier.fillMaxWidth(), colors = fieldColors)
        }
        if (p.mathA != null && p.mathB != null) {
            Text("Solve: ${p.mathA} × ${p.mathB}", style = MaterialTheme.typography.titleLarge, color = Palette.Forest900)
            OutlinedTextField(math, { math = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }

        val effectiveMinutes = if (cap != null) minutes.coerceAtMost(cap) else minutes
        val ok = reason.trim().length >= 8 && waitLeft == 0 &&
            (p.sentence == null || sentence.trim() == p.sentence) &&
            (p.mathAnswer == null || math.toIntOrNull() == p.mathAnswer) &&
            effectiveMinutes > 0

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { scope.launch { onEnter(reason.trim(), effectiveMinutes, p.level) } },
            enabled = ok, modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Sun, contentColor = Palette.Forest900),
        ) { Text(if (waitLeft > 0) "Breathe… ${waitLeft}s" else "Open for $effectiveMinutes min") }
        TextButton(onLeave, Modifier.align(Alignment.CenterHorizontally)) { Text("Never mind — take me back", color = Palette.Forest900) }
    }
}
