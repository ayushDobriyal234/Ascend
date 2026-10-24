package com.ayush.ascend.wallpaper

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.ayush.ascend.core.Progress
import com.ayush.ascend.core.graph
import com.ayush.ascend.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * #13 Live wallpaper: the Home mountain, full screen. Climber = streak / 45, sky = today's time vs goal.
 * No animation loop: it redraws when it becomes visible, on home-page swipes (parallax) and every 5 min while visible.
 */
class AscendWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = AscendEngine()

    private inner class AscendEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val tick = Runnable { refresh() }
        private var job: Job? = null
        private var visible = false
        private var progress = 0f
        private var glow = 0f
        private var parallax = 0.5f

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(tick)
            if (v) refresh() else job?.cancel()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            draw()
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPx: Int, yPx: Int) {
            parallax = xOffset
            if (visible) draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) { stop(); super.onSurfaceDestroyed(holder) }
        override fun onDestroy() { stop(); super.onDestroy() }

        private fun stop() { visible = false; handler.removeCallbacks(tick); job?.cancel() }

        private fun refresh() {
            job?.cancel()
            job = graph.scope.launch {
                runCatching {
                    val goal = graph.prefs.current().dailyGoalMin.coerceAtLeast(1)
                    progress = Progress.streak(graph.db.days().recent(60)) / Progress.SUMMIT_DAYS.toFloat()
                    glow = graph.usage.today(60_000).totalMinutes / goal.toFloat()
                }
                handler.post { draw() }
            }
            handler.postDelayed(tick, 5 * 60_000L)
        }

        private fun draw() {
            val holder = surfaceHolder
            val c = runCatching { holder.lockCanvas() }.getOrNull() ?: return
            try {
                val w = c.width.toFloat(); val h = c.height.toFloat()
                // clock-based drift: sun and clouds sit a little further along each time you look
                val drift = (System.currentTimeMillis() % 3_600_000L) / 3_600_000f
                MountainArt.draw(c, w, h, minOf(h * 0.62f, w * 1.3f), progress, glow, drift, parallax)
            } finally {
                holder.unlockCanvasAndPost(c)
            }
        }
    }
}

/** The logo scene in android.graphics, shared by the wallpaper and the weekly share card. Mirrors MountainClimb. */
object MountainArt {
    private val dusk = 0xFFE58A4E.toInt()
    private val duskLow = Palette.SunSoft.toArgb()
    private val nightLow = Palette.Forest700.toArgb()
    private val stars = Random(7).let { r -> List(70) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) } }

    /**
     * Sky fills the canvas; the mountain scene sits in the bottom [sceneH] px.
     * [glow] = today's minutes / goal: 0 bright cream → 1 dusk orange → 1.25+ forest night with stars.
     */
    fun draw(c: Canvas, w: Float, h: Float, sceneH: Float, progress: Float, glow: Float, drift: Float, parallax: Float = 0.5f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val day = glow.coerceIn(0f, 1f)
        val night = ((glow - 1f) / 0.25f).coerceIn(0f, 1f)
        fun sky(a: Int, b: Int, n: Int) = ColorUtils.blendARGB(ColorUtils.blendARGB(a, b, day), n, night)
        val top = sky(Palette.Cream.toArgb(), dusk, Palette.Forest900.toArgb())
        val bottom = sky(Palette.Sand.toArgb(), duskLow, nightLow)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null

        val y0 = h - sceneH
        val sh = sceneH
        val dp = w / 360f
        val shift = (parallax - 0.5f) * w * 0.06f

        if (night > 0f) stars.forEach { (x, y, s) ->
            p.color = Palette.Cream.toArgb()
            p.alpha = (night * (140 + 115 * s)).toInt()
            c.drawCircle(x * w, y * (y0 + sh * 0.4f), (1f + 1.6f * s) * dp, p)
        }

        // sun sinks toward the horizon as the day fills up; at night it's a pale moon
        val sun = floatArrayOf(w * 0.68f - shift, y0 + sh * (0.36f + 0.22f * day * (1 - night)))
        p.color = ColorUtils.blendARGB(Palette.Sun.toArgb(), Palette.Cream.toArgb(), night)
        p.alpha = 46
        c.drawCircle(sun[0], sun[1], sh * 0.36f, p)
        p.alpha = 255
        c.drawCircle(sun[0], sun[1], sh * (0.30f - 0.08f * night), p)

        if (night < 1f) {
            p.color = Palette.Paper.toArgb(); p.alpha = (230 * (1 - night)).toInt()
            val span = w * 1.5f
            cloud(c, p, (drift * span) % span - w * 0.25f - shift * 1.5f, y0 + sh * 0.16f, sh * 0.06f)
            cloud(c, p, ((drift * 1.7f + 0.55f) * span) % span - w * 0.25f - shift * 1.5f, y0 + sh * 0.42f, sh * 0.045f)
            p.alpha = 255
        }

        p.color = Palette.Sage.toArgb(); p.alpha = 140
        c.drawPath(Path().apply {
            moveTo(0f, y0 + sh * 0.70f); quadTo(w * 0.2f, y0 + sh * 0.55f, w * 0.42f, y0 + sh * 0.68f)
            quadTo(w * 0.7f, y0 + sh * 0.52f, w, y0 + sh * 0.66f); lineTo(w, h); lineTo(0f, h); close()
        }, p)
        p.alpha = 255
        p.color = Palette.Forest700.toArgb()
        c.drawPath(Path().apply {
            moveTo(w * 0.06f, h); lineTo(w * 0.62f, y0 + sh * 0.18f); lineTo(w * 0.70f, y0 + sh * 0.27f); lineTo(w * 0.98f, h); close()
        }, p)
        p.color = Palette.Forest900.toArgb(); p.alpha = 153
        c.drawPath(Path().apply {
            moveTo(w * 0.62f, y0 + sh * 0.18f); lineTo(w * 0.70f, y0 + sh * 0.27f); lineTo(w * 0.98f, h); lineTo(w * 0.56f, h); close()
        }, p)
        p.alpha = 255

        val trail = Path().apply {
            moveTo(w * 0.30f, h); cubicTo(w * 0.70f, y0 + sh * 0.88f, w * 0.30f, y0 + sh * 0.68f, w * 0.58f, y0 + sh * 0.55f)
            cubicTo(w * 0.78f, y0 + sh * 0.45f, w * 0.52f, y0 + sh * 0.34f, w * 0.63f, y0 + sh * 0.20f)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 10 * dp; p.strokeCap = Paint.Cap.ROUND
        p.color = Palette.Cream.toArgb(); p.alpha = 90
        c.drawPath(trail, p)
        val pm = PathMeasure(trail, false)
        val done = Path()
        pm.getSegment(0f, pm.length * progress.coerceIn(0f, 1f), done, true)
        p.alpha = 255
        c.drawPath(done, p)

        // summit flag
        val fx = w * 0.62f; val fy = y0 + sh * 0.18f; val pole = fy - 26 * dp
        p.color = Palette.Forest900.toArgb(); p.strokeWidth = 2 * dp
        c.drawLine(fx, fy, fx, pole, p)
        p.style = Paint.Style.FILL; p.color = Palette.Ember.toArgb()
        c.drawPath(Path().apply {
            moveTo(fx, pole); quadTo(fx + 10 * dp, pole, fx + 20 * dp, pole + 6 * dp); quadTo(fx + 10 * dp, pole + 12 * dp, fx, pole + 12 * dp); close()
        }, p)

        // climber, kept a little up the trail so it isn't clipped at 0 %
        val pos = FloatArray(2)
        pm.getPosTan(pm.length * progress.coerceIn(0.04f, 1f), pos, null)
        p.color = Palette.Sun.toArgb(); p.alpha = 90
        c.drawCircle(pos[0], pos[1], 17 * dp, p)
        p.alpha = 255
        c.drawCircle(pos[0], pos[1], 11 * dp, p)
        p.color = Palette.Forest900.toArgb()
        c.drawCircle(pos[0], pos[1], 6 * dp, p)
    }

    private fun cloud(c: Canvas, p: Paint, x: Float, y: Float, s: Float) {
        c.drawCircle(x, y, s, p); c.drawCircle(x - s, y + s * 0.25f, s * 0.75f, p)
        c.drawCircle(x + s * 1.05f, y + s * 0.2f, s * 0.8f, p)
        c.drawRect(x - s, y + s * 0.25f, x + s * 1.05f, y + s, p)
    }
}
