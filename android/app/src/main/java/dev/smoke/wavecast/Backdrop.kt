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
    private var glowKey: Triple<Int, Int, Int>? = null
    private var fadeKey: Triple<Int, Int, Int>? = null
    var glowY = -1f
        set(v) { if (field != v) { field = v; invalidateSelf() } }
    /** Цвет свечения: по умолчанию сиреневый, во время эфира — из обложки трека. */
    var glowColor = Ui.GLOW
        set(v) { if (field != v) { field = v; invalidateSelf() } }
    var lv = 0f
        set(v) { if (Math.abs(field - v) > 0.004f) { field = v; invalidateSelf() } }

    override fun draw(c: Canvas) {
        val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat()
        c.drawColor(Ui.BG)
        val cx = w / 2f; val cy = if (glowY >= 0) glowY else h * 0.4f
        // свечение: радиальный градиент вместо размытия, чтобы не нагружать отрисовку
        val q = (lv * 40).toInt() / 40f                       // ступеньки громкости: глазу незаметно, градиент реже
        val r = 230 * dp * (0.95f + q * 0.2f)
        val a = (0.62f + q * 0.3f).coerceAtMost(1f)
        val core = (glowColor and 0xFFFFFF) or ((a * 255).toInt() shl 24)
        val mid = (glowColor and 0xFFFFFF) or ((a * 110).toInt() shl 24)
        // градиенты пересоздаём только когда что-то поменялось, а не каждый кадр
        val key = Triple(cx.toInt() * 31 + cy.toInt(), (r / dp).toInt(), core)
        if (key != glowKey) { glowKey = key; glow.shader = RadialGradient(cx, cy, r, intArrayOf(core, mid, 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP) }
        c.drawCircle(cx, cy, r, glow)
        // сетка 26dp
        val step = 26 * dp
        var x = (cx % step); while (x < w) { c.drawLine(x, 0f, x, h, grid); x += step }
        var y = (cy % step); while (y < h) { c.drawLine(0f, y, w, y, grid); y += step }
        // к краям сетка растворяется в ночном фоне
        val fr = maxOf(w, h) * 0.62f
        val fk = Triple(cx.toInt(), cy.toInt(), fr.toInt())
        if (fk != fadeKey) { fadeKey = fk; fade.shader = RadialGradient(cx, cy, fr, intArrayOf(0, 0, Ui.BG), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w, h, fade)
    }

    override fun setAlpha(alpha: Int) {}

    companion object {
        /** Самый «живой» оттенок обложки, приглушённый до спокойного (как на странице слушателя). */
        fun glowFrom(bmp: android.graphics.Bitmap?): Int {
            if (bmp == null) return Ui.GLOW
            return try {
                val n = 16
                val s = android.graphics.Bitmap.createScaledBitmap(bmp, n, n, true)
                val hsl = FloatArray(3); var x = 0.0; var y = 0.0; var ws = 0.0; var sw = 0.0
                for (i in 0 until n) for (j in 0 until n) {
                    val c = s.getPixel(i, j)
                    val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val b = android.graphics.Color.blue(c)
                    if ((maxOf(r, g, b) - minOf(r, g, b)) / 255f < 0.08f) continue
                    androidx_hsl(r, g, b, hsl)
                    val w = hsl[1] * (1 - Math.abs(hsl[2] - 0.5f) * 1.6f)
                    if (w <= 0) continue
                    val h = Math.toRadians(hsl[0].toDouble())
                    x += Math.cos(h) * w; y += Math.sin(h) * w; ws += w; sw += hsl[1] * w
                }
                if (s !== bmp) s.recycle()
                if (ws < 6) return Ui.GLOW                                      // почти серая обложка
                val hue = ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).toFloat()
                val sat = (sw / ws * 0.85).coerceIn(0.45, 0.68).toFloat()
                hslToColor(hue, sat, 0.54f)
            } catch (e: Exception) { Ui.GLOW }
        }

        private fun androidx_hsl(r: Int, g: Int, b: Int, out: FloatArray) {
            val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
            val mx = maxOf(rf, gf, bf); val mn = minOf(rf, gf, bf); val d = mx - mn
            val l = (mx + mn) / 2f
            val sat = if (d == 0f) 0f else d / (1f - Math.abs(2f * l - 1f))
            var h = when { d == 0f -> 0f; mx == rf -> ((gf - bf) / d) % 6f; mx == gf -> (bf - rf) / d + 2f; else -> (rf - gf) / d + 4f } * 60f
            if (h < 0) h += 360f
            out[0] = h; out[1] = sat.coerceIn(0f, 1f); out[2] = l
        }

        private fun hslToColor(h: Float, s: Float, l: Float): Int {
            val c = (1f - Math.abs(2 * l - 1f)) * s
            val x = c * (1f - Math.abs((h / 60f) % 2f - 1f)); val m = l - c / 2f
            val (r, g, b) = when ((h / 60f).toInt()) {
                0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
            }
            return android.graphics.Color.rgb(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
        }
    }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
