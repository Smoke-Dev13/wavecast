package dev.smoke.wavecast

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Фон «свечение × осциллограф»: ночной цвет, мягкое сиреневое свечение
 * и сетка, которая видна у свечения и растворяется к краям.
 * [glowY] — центр свечения (px от верха), [lv] — громкость 0..1: свечение разгорается.
 */
class Backdrop(ctx: Context) : Drawable() {
    private val dp = ctx.resources.displayMetrics.density
    private val grid = Paint().apply { color = 0x0FBFB0F5; strokeWidth = maxOf(1f, dp * 0.6f) }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fade = Paint(Paint.ANTI_ALIAS_FLAG)
    var glowY = -1f
        set(v) { if (field != v) { field = v; invalidateSelf() } }
    var lv = 0f
        set(v) { if (Math.abs(field - v) > 0.004f) { field = v; invalidateSelf() } }

    override fun draw(c: Canvas) {
        val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat()
        c.drawColor(Ui.BG)
        val cx = w / 2f; val cy = if (glowY >= 0) glowY else h * 0.4f
        // свечение: радиальный градиент вместо размытия, чтобы не нагружать отрисовку
        val r = 230 * dp * (0.95f + lv * 0.2f)
        val a = (0.62f + lv * 0.3f).coerceAtMost(1f)
        val core = (Ui.GLOW and 0xFFFFFF) or ((a * 255).toInt() shl 24)
        val mid = (Ui.GLOW and 0xFFFFFF) or ((a * 110).toInt() shl 24)
        glow.shader = RadialGradient(cx, cy, r, intArrayOf(core, mid, 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, glow)
        // сетка 26dp
        val step = 26 * dp
        var x = (cx % step); while (x < w) { c.drawLine(x, 0f, x, h, grid); x += step }
        var y = (cy % step); while (y < h) { c.drawLine(0f, y, w, y, grid); y += step }
        // к краям сетка растворяется в ночном фоне
        val fr = maxOf(w, h) * 0.62f
        fade.shader = RadialGradient(cx, cy, fr, intArrayOf(0, 0, Ui.BG), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fade)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
