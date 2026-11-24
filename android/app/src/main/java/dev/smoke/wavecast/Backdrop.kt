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
 * Фон «глаза зверя в темноте»: тёплая ночь, мягкое свечение за главным глазом и пары глаз,
 * которые проступают из темноты в разных местах, моргают и гаснут.
 * [glowY] — центр свечения (px от верха), [lv] — громкость 0..1: свечение и глаза разгораются.
 */
class Backdrop(ctx: Context) : Drawable() {
    private val dp = ctx.resources.displayMetrics.density
    /** Цвет радужки (в эфире — по жанру). */
    var neonA = Ui.NEON
        set(v) { if (field != v) { field = v; invalidateSelf() } }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD) }
    private var glowKey: Triple<Int, Int, Int>? = null
    private val night = Paint(Paint.ANTI_ALIAS_FLAG)
    private var nightKey: Triple<Int, Int, Int>? = null
    var glowY = -1f
        set(v) { if (field != v) { field = v; invalidateSelf() } }
    /** Цвет свечения вокруг глаза. */
    var glowColor = Ui.GLOW
        set(v) { if (field != v) { field = v; invalidateSelf() } }
    /** Цвета глаз: [радужка, свечение, ободок, глаза вдали]. */
    var palette = Themes.BRAND.copyOf()
        set(v) { field = v; invalidateSelf() }
    /** Время в секундах — глаза вдали проступают, моргают и гаснут (задаёт главный экран). */
    var time = 0f
        set(v) { field = v; invalidateSelf() }
    /** Вспышка на сильной доле 0..1. */
    var beat = 0f
    /** Тема по жанру: ритм, вспышки, стробоскоп, цветной слой. */
    var look = Themes.DEFAULT
        set(v) { field = v; overlayShader = null; invalidateSelf() }
    private var overlayShader: RadialGradient? = null
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD) }
    private val strobe = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val eyeHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = android.graphics.BlurMaskFilter(7 * dp, android.graphics.BlurMaskFilter.Blur.NORMAL) }
    private val eyeCore = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = android.graphics.RectF()
    // пары глаз вдали: у каждой свой период (с) и сдвиг
    private val per = floatArrayOf(9f, 11.5f, 13f, 10.2f, 14.5f, 12.3f)
    private val off = floatArrayOf(0f, 3.7f, 7.1f, 1.9f, 5.3f, 9.4f)

    /** Цвет палитры: целый индекс — сам цвет, дробный — плавно между соседними. */
    fun colorAt(k: Float): Int {
        val pal = palette; val n = pal.size
        val x = ((k % n) + n) % n; val i = x.toInt(); val f = x - i
        return android.animation.ArgbEvaluator().evaluate(f, pal[i], pal[(i + 1) % n]) as Int
    }

    var lv = 0f
        set(v) { if (Math.abs(field - v) > 0.004f) { field = v; invalidateSelf() } }

    private fun hash(a: Int, b: Int): Float {
        var h = a * 374761393 + b * 668265263; h = (h xor (h ushr 13)) * 1274126177; h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    override fun draw(c: Canvas) {
        val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat()
        c.drawColor(Ui.BG)
        val cx = w / 2f; val cy = if (glowY >= 0) glowY else h * 0.4f
        // тёплая темнота: к центру чуть светлее, к краям — чёрная
        val nr = maxOf(w, h) * 0.75f
        val nk = Triple(cx.toInt(), cy.toInt(), nr.toInt())
        if (nk != nightKey) { nightKey = nk; night.shader = RadialGradient(cx, cy, nr, intArrayOf(0xFF1A0E05.toInt(), 0xFF070402.toInt(), 0xFF000000.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w, h, night)
        // свечение за глазом: дышит громкостью и вспыхивает на долях
        val q = (lv * 40).toInt() / 40f
        val r = 230 * dp * (0.95f + q * 0.25f)
        val a = (0.42f + q * 0.35f + beat * 0.25f * look.beat).coerceAtMost(1f)
        val core = (glowColor and 0xFFFFFF) or ((a * 255).toInt() shl 24)
        val key = Triple(cy.toInt() * 31 + cx.toInt(), (r / dp).toInt(), core)
        if (key != glowKey) { glowKey = key; glow.shader = RadialGradient(cx, cy, r, intArrayOf(core, (core and 0xFFFFFF) or ((a * 90).toInt() shl 24), 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP) }
        c.drawCircle(cx, cy, r, glow)
        // глаза вдали: проступают в случайном месте, держатся, моргают, гаснут — и появляются в другом
        for (k in per.indices) {
            val p = per[k] * look.bk.coerceIn(0.6f, 1.6f)
            val tt = time + off[k]; val cyc = (tt / p).toInt(); val ph = tt / p - cyc
            val env = when { ph < 0.12f -> ph / 0.12f; ph < 0.78f -> 1f; ph < 0.9f -> 1f - (ph - 0.78f) / 0.12f; else -> 0f }
            if (env <= 0f) continue
            var ex = w * (0.07f + 0.86f * hash(k, cyc)); val ey = h * (0.07f + 0.86f * hash(k + 17, cyc))
            if (Math.abs(ey - cy) < 150 * dp && Math.abs(ex - cx) < w * 0.32f) ex = if (ex < cx) w * 0.08f else w * 0.92f   // не поверх главного глаза
            val bl = Math.abs(ph - 0.45f).let { if (it < 0.02f) 0.1f else 1f } * Math.abs(ph - 0.66f).let { if (it < 0.02f) 0.1f else 1f }
            val s = (3.5f + 3f * hash(k + 5, cyc)) * dp; val gap = s * 1.9f
            val col = palette[if (k % 2 == 0) 0 else 3 % palette.size]
            val al = (env * (0.65f + lv * 0.5f + beat * 0.3f)).coerceAtMost(1f)
            for (side in intArrayOf(-1, 1)) {
                val x = ex + side * gap / 2f
                oval.set(x - s, ey - s * 0.5f * bl, x + s, ey + s * 0.5f * bl)
                eyeHalo.color = col; eyeHalo.alpha = (al * 200).toInt()
                c.drawOval(oval.left - s * 0.6f, oval.top - s * 0.6f, oval.right + s * 0.6f, oval.bottom + s * 0.6f, eyeHalo)
                eyeCore.color = col; eyeCore.alpha = (al * 255).toInt()
                c.drawOval(oval, eyeCore)
            }
        }
        // огонь снизу (рок) или тёплый свет сверху (фолк)
        if (look.overlay != 0) {
            if (overlayShader == null) {
                val ox = if (look.overlayTop) w * 0.3f else w * 0.5f; val oy = if (look.overlayTop) h * 0.2f else h * 1.05f
                val rgb = look.overlay and 0xFFFFFF
                overlayShader = RadialGradient(ox, oy, maxOf(w, h) * 0.6f, intArrayOf(rgb or 0x80000000.toInt(), rgb or 0x30000000, 0), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            }
            overlayPaint.shader = overlayShader; c.drawRect(0f, 0f, w, h, overlayPaint)
        }
        // стробоскоп на ударе (клуб)
        if (look.strobe && beat > 0.3f) { strobe.alpha = (beat * 36).toInt(); c.drawRect(0f, 0f, w, h, strobe) }
    }

    override fun setAlpha(alpha: Int) {}

    companion object {
        /** Палитра обложки для неона: до 4 заметных оттенков (не ближе 45°) на полной яркости,
         *  не хватает — соседние к главному; серая или без обложки — малиновый, бирюзовый, фиолетовый, синий. */
        fun paletteFrom(bmp: android.graphics.Bitmap?): IntArray {
            val def = Themes.BRAND.copyOf()
            if (bmp == null) return def
            return try {
                val n = 16
                val s = android.graphics.Bitmap.createScaledBitmap(bmp, n, n, true)
                val hsl = FloatArray(3); val bins = FloatArray(24); var total = 0f
                for (i in 0 until n) for (j in 0 until n) {
                    val c = s.getPixel(i, j)
                    val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val b = android.graphics.Color.blue(c)
                    if ((maxOf(r, g, b) - minOf(r, g, b)) / 255f < 0.08f) continue
                    androidx_hsl(r, g, b, hsl)
                    val w = hsl[1] * (1 - Math.abs(hsl[2] - 0.5f) * 1.6f)
                    if (w <= 0) continue
                    bins[(hsl[0] / 15f).toInt() % 24] += w; total += w
                }
                if (s !== bmp) s.recycle()
                if (total < 6) return def                                       // почти серая обложка
                fun sm(i: Int) = bins[(i + 23) % 24] * 0.5f + bins[i] + bins[(i + 1) % 24] * 0.5f
                val order = (0 until 24).sortedByDescending { sm(it) }
                val a = order[0]; val pick = mutableListOf(a)
                for (i in order) {
                    if (pick.size >= 4 || sm(i) < sm(a) * 0.15f) break
                    if (pick.all { j -> minOf(Math.abs(i - j), 24 - Math.abs(i - j)) >= 3 }) pick.add(i)
                }
                val hs = pick.map { it * 15f + 7.5f }.toMutableList()
                for (d in floatArrayOf(40f, -40f, 80f)) if (hs.size < 4) hs.add((hs[0] + d + 360f) % 360f)
                IntArray(4) { hslToColor(hs[it], 1f, 0.55f) }
            } catch (e: Exception) { def }
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
