package com.ayush.ascend.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.ayush.ascend.core.graph
import com.ayush.ascend.ui.theme.Palette

/** Card with the cream/forest surface used everywhere. */
@Composable
fun AscendCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp)
    ) { content() }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) { ctx.graph.usage.icon(pkg)?.toBitmap(96, 96)?.asImageBitmap() }
    if (bmp != null) Image(bmp, null, Modifier.size(size).clip(RoundedCornerShape(size / 4)))
    else Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(MaterialTheme.colorScheme.primaryContainer))
}

/** Thin pill bar; turns amber at 80 %, ember at 100 %. */
@Composable
fun UsageBar(fraction: Float, modifier: Modifier = Modifier) {
    val f = fraction.coerceIn(0f, 1f)
    val color = when {
        fraction >= 1f -> Palette.Ember
        fraction >= 0.8f -> Palette.Sun
        else -> MaterialTheme.colorScheme.tertiary
    }
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val r = size.height / 2
        drawLine(track, Offset(r, r), Offset(size.width - r, r), size.height, StrokeCap.Round)
        if (f > 0f) drawLine(color, Offset(r, r), Offset(r + (size.width - 2 * r) * f, r), size.height, StrokeCap.Round)
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    pendingText: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pendingText != null) Text(pendingText, style = MaterialTheme.typography.labelMedium, color = Palette.Ember)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
fun Chip(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer, textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = textColor,
        modifier = Modifier.clip(CircleShape).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            leading?.invoke()
            // long values ("2h 17m") step down a size instead of wrapping
            Text(value, style = if (value.length > 5) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                maxLines = 1, softWrap = false)
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val CoinRim = Color(0xFFC98A12)

/** The AC coin: gold disc with the Ascend peak stamped in the middle. */
@Composable
fun Coin(size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        val c = center
        drawCircle(CoinRim, r)
        drawCircle(Palette.Sun, r * 0.84f)
        drawCircle(CoinRim, r * 0.64f, style = Stroke(r * 0.08f))
        val peak = Path().apply {
            moveTo(c.x - r * 0.44f, c.y + r * 0.28f); lineTo(c.x - r * 0.06f, c.y - r * 0.34f)
            lineTo(c.x + r * 0.10f, c.y - r * 0.08f); lineTo(c.x + r * 0.22f, c.y - r * 0.22f)
            lineTo(c.x + r * 0.46f, c.y + r * 0.28f); close()
        }
        drawPath(peak, Palette.Forest700)
    }
}

@Composable
fun CoinAmount(text: String, style: TextStyle = MaterialTheme.typography.titleMedium, color: Color = Color.Unspecified, coin: Dp = 18.dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Coin(coin)
        Text(text, style = style, color = color)
    }
}

/**
 * The climb: a winding trail up a peak, sun behind it; the climber dot sits at [progress] of the trail.
 * Echoes the logo — this is the hero of the Home screen.
 */
@Composable
fun MountainClimb(progress: Float, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "climb")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(45_000, easing = LinearEasing)), label = "drift")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2_200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        // sky + breathing sun
        drawRect(Palette.Sand)
        val sun = Offset(w * 0.68f, h * 0.36f)
        drawCircle(Palette.Sun.copy(alpha = 0.18f), radius = h * (0.36f + 0.04f * breathe), center = sun)
        drawCircle(Palette.Sun, radius = h * 0.30f, center = sun)
        // drifting clouds, like the logo's
        fun cloud(x: Float, y: Float, s: Float) {
            val c = Palette.Paper.copy(alpha = 0.9f)
            drawCircle(c, s, Offset(x, y)); drawCircle(c, s * 0.75f, Offset(x - s * 1.0f, y + s * 0.25f))
            drawCircle(c, s * 0.8f, Offset(x + s * 1.05f, y + s * 0.2f))
            drawRect(c, Offset(x - s * 1.0f, y + s * 0.25f), androidx.compose.ui.geometry.Size(s * 2.05f, s * 0.75f))
        }
        val span = w * 1.5f
        cloud((drift * span) % span - w * 0.25f, h * 0.16f, h * 0.06f)
        cloud(((drift * 1.7f + 0.55f) * span) % span - w * 0.25f, h * 0.42f, h * 0.045f)
        // far hills
        val far = Path().apply {
            moveTo(0f, h * 0.70f); quadraticTo(w * 0.2f, h * 0.55f, w * 0.42f, h * 0.68f)
            quadraticTo(w * 0.7f, h * 0.52f, w, h * 0.66f); lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(far, Palette.Sage.copy(alpha = 0.55f))
        // main peak
        val peak = Path().apply {
            moveTo(w * 0.06f, h); lineTo(w * 0.62f, h * 0.18f); lineTo(w * 0.70f, h * 0.27f)
            lineTo(w * 0.98f, h); close()
        }
        drawPath(peak, Palette.Forest700)
        val shade = Path().apply { moveTo(w * 0.62f, h * 0.18f); lineTo(w * 0.70f, h * 0.27f); lineTo(w * 0.98f, h); lineTo(w * 0.56f, h); close() }
        drawPath(shade, Palette.Forest900.copy(alpha = 0.6f))
        // trail
        val trail = Path().apply {
            moveTo(w * 0.30f, h); cubicTo(w * 0.70f, h * 0.88f, w * 0.30f, h * 0.68f, w * 0.58f, h * 0.55f)
            cubicTo(w * 0.78f, h * 0.45f, w * 0.52f, h * 0.34f, w * 0.63f, h * 0.20f)
        }
        drawPath(trail, Palette.Cream.copy(alpha = 0.35f), style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round))
        val pm = PathMeasure().apply { setPath(trail, false) }
        val done = Path()
        val len = pm.length
        pm.getSegment(0f, len * progress.coerceIn(0f, 1f), done, true)
        drawPath(done, Palette.Cream, style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round))
        // summit flag, waving
        val top = Offset(w * 0.62f, h * 0.18f)
        val poleTop = top - Offset(0f, 26.dp.toPx())
        drawLine(Palette.Forest900, top, poleTop, 2.dp.toPx(), StrokeCap.Round)
        val wave = 4.dp.toPx() * (breathe - 0.5f)
        drawPath(Path().apply {
            moveTo(poleTop.x, poleTop.y); quadraticTo(poleTop.x + 10.dp.toPx(), poleTop.y + wave, poleTop.x + 20.dp.toPx(), poleTop.y + 6.dp.toPx())
            quadraticTo(poleTop.x + 10.dp.toPx(), poleTop.y + 12.dp.toPx() - wave, poleTop.x, poleTop.y + 12.dp.toPx()); close()
        }, Palette.Ember)
        // climber with a pulse ring (kept a little up the trail so it isn't clipped at 0 %)
        val pos = pm.getPosition(len * progress.coerceIn(0.04f, 1f)) - Offset(0f, 2.dp.toPx() * breathe)
        drawCircle(Palette.Sun.copy(alpha = 0.5f * (1f - breathe)), radius = (11 + 10 * breathe).dp.toPx(), center = pos)
        drawCircle(Palette.Sun, radius = 11.dp.toPx(), center = pos)
        drawCircle(Palette.Forest900, radius = 6.dp.toPx(), center = pos)
    }
}
