package com.ayush.ascend.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import com.ayush.ascend.core.Mood
import com.ayush.ascend.core.Predictor
import com.ayush.ascend.core.Report
import com.ayush.ascend.core.graph
import com.ayush.ascend.data.formatMinutes
import com.ayush.ascend.ui.LocalSnackbar
import com.ayush.ascend.ui.components.AscendCard
import com.ayush.ascend.ui.components.SectionLabel
import com.ayush.ascend.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

@Composable
fun InsightsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.prefs.settings.collectAsStateWithLifecycle()
    val reasons by g.db.opens().observeRecentReasons(15).collectAsStateWithLifecycle(emptyList())
    val sampleCount by g.db.samples().observeCount().collectAsStateWithLifecycle(0)
    val moodSince = remember { System.currentTimeMillis() - 30L * 24 * 3_600_000 }
    val moods by g.db.moods().observeSince(moodSince).collectAsStateWithLifecycle(emptyList())
    val reports by g.db.reports().observeAll().collectAsStateWithLifecycle(emptyList())
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val week by produceState(emptyList<Pair<LocalDate, Int>>()) {
        value = withContext(Dispatchers.Default) {
            (6 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }.map { it to g.usage.day(it).totalMinutes }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp, 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Insights", style = MaterialTheme.typography.headlineMedium)

        SectionLabel("Last 7 days")
        AscendCard {
            Column {
                val avg = week.map { it.second }.average().takeIf { !it.isNaN() }?.toInt() ?: 0
                Text("${formatMinutes(avg)} / day average", style = MaterialTheme.typography.titleLarge)
                val goal = s.dailyGoalMin
                val max = (week.maxOfOrNull { it.second } ?: 0).coerceAtLeast(goal).coerceAtLeast(1)
                val barOk = MaterialTheme.colorScheme.tertiary
                val goalLine = MaterialTheme.colorScheme.outline
                Canvas(Modifier.fillMaxWidth().height(150.dp).padding(top = 12.dp)) {
                    val n = week.size.coerceAtLeast(1)
                    val slot = size.width / n
                    val bw = slot * 0.55f
                    week.forEachIndexed { i, (_, m) ->
                        val h = size.height * m / max
                        drawRoundRect(if (m > goal) Palette.Ember else barOk, Offset(i * slot + (slot - bw) / 2, size.height - h),
                            Size(bw, h), CornerRadius(8f, 8f))
                    }
                    val gy = size.height - size.height * goal / max
                    drawLine(goalLine, Offset(0f, gy), Offset(size.width, gy), 2f)
                }
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { (d, _) ->
                        Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }

        SectionLabel("How apps make you feel")
        AscendCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val apps = Mood.byApp(moods)
                if (apps.isEmpty()) Text("After 3+ min in a distracting app, Ascend asks how you feel (max ${Mood.MAX_PER_DAY}× a day). Answers show up here.",
                    style = MaterialTheme.typography.bodySmall)
                Mood.highlight(apps) { g.usage.label(it) }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                apps.take(6).forEach { a ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(g.usage.label(a.pkg), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(Mood.faces.entries.joinToString("  ") { (m, f) -> "$f ${a.pct[m]}%" }, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        SectionLabel("Weekly reports")
        if (reports.isEmpty()) Text("Your first report card arrives Sunday at 7 PM.", style = MaterialTheme.typography.bodySmall)
        reports.forEach { r ->
            AscendCard(onClick = { nav.navigate("report?week=${r.weekStart}") }) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(r.grade, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(end = 14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(weekRange(r.weekStart), style = MaterialTheme.typography.titleMedium)
                        Text("${formatMinutes(r.avgMinutes)}/day · ${Report.arrow(r.trendPct)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        TextButton({ scope.launch {
            val r = withContext(Dispatchers.Default) { Report.build(g) }
            g.db.reports().upsert(r)
            nav.navigate("report?week=${r.weekStart}")
        } }) { Text("Make this week's report now") }

        SectionLabel("When you slip")
        AscendCard {
            Column {
                val w = g.predictor.parseWeights(s.modelWeights)
                if (w == null) {
                    Text("Still learning", style = MaterialTheme.typography.titleMedium)
                    Text("$sampleCount unlocks recorded. The model trains once it has ${Predictor.MIN_SAMPLES} labelled unlocks with both good and bad sessions — usually 2–4 days.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    val risk = g.predictor.hourlyRisk(w)
                    Text("Risk by hour (typical weekday)", style = MaterialTheme.typography.titleMedium)
                    Canvas(Modifier.fillMaxWidth().height(36.dp).padding(top = 8.dp)) {
                        val cw = size.width / 24
                        risk.forEachIndexed { h, p ->
                            drawRoundRect(lerp(Palette.Sand, Palette.Ember, p.toFloat()), Offset(h * cw + 1, 0f), Size(cw - 2, size.height), CornerRadius(4f, 4f))
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        listOf("12a", "6a", "12p", "6p").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall) }
                    }
                    val worst = risk.withIndex().maxByOrNull { it.value }?.index ?: 0
                    Text("Riskiest hour: $worst:00. Accuracy ${(s.modelAccuracy * 100).toInt()}% on ${s.modelSamples} unlocks.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                OutlinedButton({ scope.launch {
                    val r = withContext(Dispatchers.Default) { g.predictor.labelAndTrain() }
                    snackbar.showSnackbar(r?.let { "Trained on ${it.second} unlocks · ${(it.first * 100).toInt()}% accuracy" } ?: "Not enough data yet")
                } }, Modifier.padding(top = 8.dp)) { Text("Train now") }
            }
        }

        SectionLabel("What you told the gate")
        if (reasons.isEmpty()) Text("Your reasons will show up here. Reading them back is weirdly effective.", style = MaterialTheme.typography.bodySmall)
        reasons.forEach { e ->
            AscendCard {
                Column {
                    Text("“${e.reason}”", style = MaterialTheme.typography.bodyLarge)
                    Text("${g.usage.label(e.pkg)} · ${e.minutes} min · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.ts))}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
