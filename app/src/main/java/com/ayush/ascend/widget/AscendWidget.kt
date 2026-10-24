package com.ayush.ascend.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ayush.ascend.R
import com.ayush.ascend.core.Progress
import com.ayush.ascend.core.graph
import com.ayush.ascend.data.formatMinutes
import com.ayush.ascend.ui.MainActivity
import com.ayush.ascend.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** #12 Home-screen widget: today's time vs goal, unlocks, altitude. */
class AscendWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val g = context.graph
        val u = withContext(Dispatchers.Default) { g.usage.today(0) }
        val goal = g.prefs.current().dailyGoalMin
        val streak = Progress.streak(g.db.days().recent(60))
        val cream = ColorProvider(Palette.Cream, Palette.Cream)
        val sand = ColorProvider(Palette.Sand, Palette.Sand)
        provideContent {
            Column(
                GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(Palette.Forest900).padding(16.dp)
                    .clickable(actionStartActivity<MainActivity>()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(ImageProvider(R.drawable.ascend_logo), "Ascend", GlanceModifier.size(26.dp).cornerRadius(7.dp))
                    Spacer(GlanceModifier.width(8.dp))
                    Text("${Progress.altitude(streak)} m · day $streak", style = TextStyle(color = sand, fontSize = 12.sp))
                }
                Spacer(GlanceModifier.height(10.dp))
                Text(formatMinutes(u.totalMinutes), style = TextStyle(color = cream, fontSize = 30.sp, fontWeight = FontWeight.Bold))
                Text("of ${formatMinutes(goal)} · ${u.unlocks} unlocks", style = TextStyle(color = sand, fontSize = 12.sp))
                Spacer(GlanceModifier.height(10.dp))
                LinearProgressIndicator(
                    progress = (u.totalMinutes / goal.toFloat()).coerceIn(0f, 1f),
                    modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                    color = ColorProvider(if (u.totalMinutes > goal) Palette.Ember else Palette.Sun, if (u.totalMinutes > goal) Palette.Ember else Palette.Sun),
                    backgroundColor = ColorProvider(Color(0xFF1C4433), Color(0xFF1C4433)),
                )
            }
        }
    }
}

class AscendWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AscendWidget()
}
