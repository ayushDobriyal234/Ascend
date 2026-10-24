package com.ayush.ascend.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ayush.ascend.R
import com.ayush.ascend.core.Progress
import com.ayush.ascend.core.Report
import com.ayush.ascend.core.graph
import com.ayush.ascend.data.WeeklyReport
import com.ayush.ascend.data.formatMinutes
import com.ayush.ascend.ui.LocalSnackbar
import com.ayush.ascend.ui.components.AscendCard
import com.ayush.ascend.ui.components.SectionLabel
import com.ayush.ascend.ui.components.StatTile
import com.ayush.ascend.ui.components.ToggleRow
import com.ayush.ascend.ui.theme.Palette
import com.ayush.ascend.wallpaper.MountainArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** #16 Weekly report card. [week] = weekStart, or null for the latest. */
@Composable
fun ReportScreen(nav: NavController, week: String?) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.prefs.settings.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val r by produceState<WeeklyReport?>(null, week) {
        value = withContext(Dispatchers.IO) { if (week != null) g.db.reports().get(week) else g.db.reports().latest() }
    }

    SubScreen("Weekly report", nav) {
        val rep = r
        if (rep == null) {
            Text("Your first report card arrives Sunday at 7 PM.", style = MaterialTheme.typography.bodyMedium)
            return@SubScreen
        }
        AscendCard { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(rep.grade, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(weekRange(rep.weekStart), style = MaterialTheme.typography.titleMedium)
                Text("${Report.arrow(rep.trendPct)} screen time vs last week", style = MaterialTheme.typography.bodySmall,
                    color = if (rep.trendPct > 0) Palette.Ember else MaterialTheme.colorScheme.tertiary)
                Text("${Report.arrow(rep.unlocksTrendPct)} unlocks", style = MaterialTheme.typography.bodySmall,
                    color = if (rep.unlocksTrendPct > 0) Palette.Ember else MaterialTheme.colorScheme.tertiary)
            }
        } }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(formatMinutes(rep.totalMinutes), "total", Modifier.weight(1f).fillMaxHeight())
            StatTile(formatMinutes(rep.avgMinutes), "per day", Modifier.weight(1f).fillMaxHeight())
            StatTile("${rep.daysUnderGoal}/7", "under goal", Modifier.weight(1f).fillMaxHeight())
        }
        AscendCard { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Line("Best day", "${Report.dayName(rep.bestDay)} · ${formatMinutes(rep.bestMinutes)}")
            Line("Worst day", "${Report.dayName(rep.worstDay)} · ${formatMinutes(rep.worstMinutes)}")
            Line("Summit days", "${rep.summitDays}")
            Line("Mood", rep.mood)
            if (rep.timeCost.isNotEmpty()) Text("That's ${rep.timeCost}.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        SectionLabel("Top apps")
        AscendCard { Column {
            rep.apps.forEachIndexed { i, (pkg, min) ->
                if (i > 0) HorizontalDivider()
                Line(g.usage.label(pkg), formatMinutes(min))
            }
        } }
        SectionLabel("Share")
        AscendCard { Column {
            ToggleRow("Private", "Leave app names off the image.", s.sharePrivate) { on ->
                scope.launch { g.prefs.update { p, k -> p[k.sharePrivate] = on } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ scope.launch {
                    val f = withContext(Dispatchers.Default) { ReportCard.file(ctx, rep, s.sharePrivate) }
                    ReportCard.share(ctx, f)
                } }) { Text("Share") }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) OutlinedButton({ scope.launch {
                    val ok = withContext(Dispatchers.Default) { ReportCard.saveToGallery(ctx, rep, s.sharePrivate) }
                    snackbar.showSnackbar(if (ok) "Saved to Pictures/Ascend" else "Couldn't save the image")
                } }) { Text("Save to gallery") }
            }
        } }
    }
}

@Composable
private fun Line(label: String, value: String) = Row {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Text(value, style = MaterialTheme.typography.labelLarge)
}

fun weekRange(weekStart: String): String {
    val d = LocalDate.parse(weekStart)
    val f = DateTimeFormatter.ofPattern("MMM d")
    return "${d.format(f)} – ${d.plusDays(6).format(f)}"
}

/** 1080×1920 story image: logo, grade, key stats, the mountain. */
object ReportCard {
    private const val W = 1080
    private const val H = 1920

    fun render(ctx: Context, r: WeeklyReport, private: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        MountainArt.draw(c, W.toFloat(), H.toFloat(), 780f, r.streak / Progress.SUMMIT_DAYS.toFloat(), 0f, 0.3f)

        val heavy = ResourcesCompat.getFont(ctx, R.font.montserrat_extrabold)
        val bold = ResourcesCompat.getFont(ctx, R.font.montserrat_bold)
        val body = ResourcesCompat.getFont(ctx, R.font.poppins_medium)
        fun text(s: String, x: Float, y: Float, size: Float, face: android.graphics.Typeface?, color: Int, align: Paint.Align = Paint.Align.LEFT) =
            c.drawText(s, x, y, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = face; this.color = color; textAlign = align })
        val ink = Palette.Forest900.toArgb()
        val soft = Palette.Forest700.toArgb()

        BitmapFactory.decodeResource(ctx.resources, R.drawable.ascend_logo)?.let { logo ->
            val shader = android.graphics.BitmapShader(Bitmap.createScaledBitmap(logo, 130, 130, true),
                android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            c.save(); c.translate(80f, 100f)
            c.drawRoundRect(RectF(0f, 0f, 130f, 130f), 32f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            c.restore()
        }
        text("Ascend", 240f, 165f, 64f, heavy, ink)
        text("Week of ${weekRange(r.weekStart)}", 240f, 218f, 36f, body, soft)

        text(r.grade, 80f, 600f, 340f, heavy, ink)
        text("${Report.arrow(r.trendPct)} vs last week", 1000f, 430f, 44f, bold,
            if (r.trendPct > 0) Palette.Ember.toArgb() else soft, Paint.Align.RIGHT)
        text("${r.daysUnderGoal}/7 days under goal", 1000f, 500f, 36f, body, soft, Paint.Align.RIGHT)

        var y = 720f
        fun row(label: String, value: String) {
            text(label, 80f, y, 40f, body, soft)
            text(value, 1000f, y, 44f, bold, ink, Paint.Align.RIGHT)
            y += 72f
        }
        row("Total", formatMinutes(r.totalMinutes))
        row("Daily average", formatMinutes(r.avgMinutes))
        row("Best day", "${Report.dayName(r.bestDay)} · ${formatMinutes(r.bestMinutes)}")
        row("Worst day", "${Report.dayName(r.worstDay)} · ${formatMinutes(r.worstMinutes)}")
        row("Mood", r.mood)
        if (!private && r.apps.isNotEmpty()) {
            row("Top apps", r.apps.joinToString(" · ") { ctx.graph.usage.label(it.first).take(12) })
        }
        if (r.timeCost.isNotEmpty()) text("= ${r.timeCost}", 80f, y + 10f, 38f, body, soft)

        text("${Progress.altitude(r.streak)} m · day ${r.streak} of ${Progress.SUMMIT_DAYS}", W / 2f, H - 70f, 40f, bold,
            Palette.Cream.toArgb(), Paint.Align.CENTER)
        return bmp
    }

    fun file(ctx: Context, r: WeeklyReport, private: Boolean): File {
        val f = File(ctx.cacheDir, "shared").apply { mkdirs() }.resolve("ascend_week.png")
        val bmp = render(ctx, r, private)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    fun share(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share your week").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** API 29+ (scoped storage, no permission needed). */
    fun saveToGallery(ctx: Context, r: WeeklyReport, private: Boolean): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ascend_week_${r.weekStart}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Ascend")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { render(ctx, r, private).compress(Bitmap.CompressFormat.PNG, 100, it) } != null
    }.getOrDefault(false)
}
