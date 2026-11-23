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
 * Фон в стиле киберпанк: ночной цвет, два неоновых сияния (малиновое и бирюзовое/из обложки), сканлайны
 * и сетка, которая видна у свечения и растворяется к краям.
 * [glowY] — центр свечения (px от верха), [lv] — громкость 0..1: свечение разгорается.
 */
class Backdrop(ctx: Context) : Drawable() {
    private val dp = ctx.resources.displayMetrics.density
    private val gridH = Paint().apply { color = 0x1A00E5FF; strokeWidth = maxOf(1f, dp * 0.6f) }
    private val gridV = Paint().apply { color = 0x1AFF2BD6; strokeWidth = maxOf(1f, dp * 0.6f) }
    /** Первый неон (по умолчанию малиновый), в эфире — главный цвет обложки. */
    var neonA = Ui.NEON
        set(v) { if (field != v) { field = v; gridV.color = (v and 0xFFFFFF) or 0x1A000000; invalidateSelf() } }
    private val scan = Paint().apply { color = 0x30000000; strokeWidth = maxOf(1f, dp * 0.5f) }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD) }
    private val glowA = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD) }
    private var glowAKey: Triple<Int, Int, Int>? = null
    private val fade = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowKey: Triple<Int, Int, Int>? = null
    private var fadeKey: Triple<Int, Int, Int>? = null
    var glowY = -1f
        set(v) { if (field != v) { field = v; invalidateSelf() } }
    /** Цвет свечения: по умолчанию сиреневый, во время эфира — из обложки трека. */
    var glowColor = Ui.GLOW
        set(v) { if (field != v) { field = v; gridH.color = (v and 0xFFFFFF) or 0x1A000000; invalidateSelf() } }
    /** Палитра обложки (4 неона): пятна плывут по экрану из угла в угол и мигают. */
    var palette = intArrayOf(Ui.NEON, Ui.GLOW, 0xFF8A2BFF.toInt(), 0xFF2B6BFF.toInt())
        set(v) { field = v; invalidateSelf() }
    /** Время в секундах — двигает пятна (задаёт главный экран, пока он открыт). */
    var time = 0f
        set(v) { field = v; invalidateSelf() }
    /** Вспышка на сильной доле 0..1 — пятна разом ярче. */
    var beat = 0f
    /** Тема по жанру: ритм мигания, сила вспышек, стробоскоп, цветной слой. */
    var look = Themes.DEFAULT
        set(v) { field = v; overlayShader = null; invalidateSelf() }
    private var overlayShader: RadialGradient? = null
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD) }
    private val strobe = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD) }
    private val blobShaders = arrayOfNulls<RadialGradient>(4)
    private val blobKeys = IntArray(4) { -1 }
    private var blobR = 0f
    private val m = android.graphics.Matrix()
    // свои пути и ритмы у каждого пятна: частоты, сдвиги фаз, период мигания (с)
    private val fx = floatArrayOf(0.17f, 0.13f, 0.21f, 0.11f); private val fy = floatArrayOf(0.14f, 0.19f, 0.10f, 0.16f)
    private val ph = floatArrayOf(0f, 2.1f, 4.2f, 1.3f); private val blinkT = floatArrayOf(0.9f, 1.2f, 1.05f, 1.4f)

    /** Цвет «по кругу палитры» в момент [time] со сдвигом [k] — для волны и кольца кнопки. */
    fun colorAt(k: Float): Int {
        val pal = palette; val n = pal.size
        val x = ((time / 1.6f + k) % n + n) % n
        val i = x.toInt(); var f = x - i; f = f * f * (3 - 2 * f)
        val a = FloatArray(3); val b = FloatArray(3)
        android.graphics.Color.colorToHSV(pal[i], a); android.graphics.Color.colorToHSV(pal[(i + 1) % n], b)
        val d = ((b[0] - a[0] + 540f) % 360f) - 180f
        return android.graphics.Color.HSVToColor(floatArrayOf((a[0] + d * f + 360f) % 360f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f))
    }

    var lv = 0f
        set(v) { if (Math.abs(field - v) > 0.004f) { field = v; invalidateSelf() } }

    override fun draw(c: Canvas) {
        val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat()
        c.drawColor(Ui.BG)
        val cx = w / 2f; val cy = if (glowY >= 0) glowY else h * 0.4f
        // свечение: радиальный градиент вместо размытия, чтобы не нагружать отрисовку
        val q = (lv * 40).toInt() / 40f                       // ступеньки громкости: глазу незаметно, градиент реже
        val r = 200 * dp * (0.95f + q * 0.2f)
        val a = (0.85f + q * 0.15f).coerceAtMost(1f)
        val core = (glowColor and 0xFFFFFF) or ((a * 255).toInt() shl 24)
        val mid = (glowColor and 0xFFFFFF) or ((a * 110).toInt() shl 24)
        // киберпанк: малиновый неон слева (постоянный) + второй неон справа (бирюза или цвет обложки), складываются
        // градиенты пересоздаём только когда что-то поменялось, а не каждый кадр
        val ax = cx - 62 * dp; val bx = cx + 58 * dp; val by = cy + 10 * dp
        val ra = 200 * dp * (0.97f + q * 0.1f)
        val ka = Triple(ax.toInt() * 31 + cy.toInt(), (ra / dp).toInt(), neonA)
        if (ka != glowAKey) { glowAKey = ka
            val rgb = neonA and 0xFFFFFF
            glowA.shader = RadialGradient(ax, cy, ra, intArrayOf(rgb or 0xF2000000.toInt(), rgb or 0x66000000, 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP) }
        c.drawCircle(ax, cy, ra, glowA)
        val key = Triple(bx.toInt() * 31 + by.toInt(), (r / dp).toInt(), core)
        if (key != glowKey) { glowKey = key; glow.shader = RadialGradient(bx, by, r, intArrayOf(core, mid, 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP) }
        c.drawCircle(bx, by, r, glow)
        // сетка 26dp: горизонтали бирюзовые, вертикали малиновые
        val step = 26 * dp
        var x = (cx % step); while (x < w) { c.drawLine(x, 0f, x, h, gridV); x += step }
        var y = (cy % step); while (y < h) { c.drawLine(0f, y, w, y, gridH); y += step }
        // к краям сетка растворяется в ночном фоне
        val fr = maxOf(w, h) * 0.62f
        val fk = Triple(cx.toInt(), cy.toInt(), fr.toInt())
        if (fk != fadeKey) { fadeKey = fk; fade.shader = RadialGradient(cx, cy, fr, intArrayOf(0, 0, Ui.BG), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w, h, fade)
        // живой неон: 4 пятна цветов обложки плывут из угла в угол, мигают, на сильной доле вспыхивают
        val br = maxOf(w, h) * 0.42f
        if (br != blobR) { blobR = br; blobKeys.fill(-1) }
        for (k in 0 until 4) {
            val col = palette[k % palette.size]
            if (blobKeys[k] != col) {
                blobKeys[k] = col; val rgb = col and 0xFFFFFF
                blobShaders[k] = RadialGradient(0f, 0f, br, intArrayOf(rgb or 0xFF000000.toInt(), rgb or 0x8C000000.toInt(), 0), floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP)
            }
            val bx = w * (0.5f + 0.62f * Math.sin((time * fx[k] * 6.283f + ph[k]).toDouble()).toFloat())
            val by = h * (0.5f + 0.6f * Math.sin((time * fy[k] * 6.283f + ph[k] * 1.7f).toDouble()).toFloat())
            val blink = 0.5f + 0.5f * Math.sin((time / (blinkT[k] * look.bk) * 6.283f + ph[k]).toDouble()).toFloat()
            val al = ((0.14f + 0.6f * blink) * (0.75f + lv * 0.9f) * (1f + beat * 1.4f * look.beat)).coerceAtMost(1f)
            m.setTranslate(bx, by); blobShaders[k]!!.setLocalMatrix(m)
            blob.shader = blobShaders[k]; blob.alpha = (al * 255).toInt()
            c.drawCircle(bx, by, br, blob)
        }
        // огонь снизу (рок) или тёплый свет сверху (фолк)
        if (look.overlay != 0) {
            if (overlayShader == null) {
                val ox = if (look.overlayTop) w * 0.3f else w * 0.5f; val oy = if (look.overlayTop) h * 0.2f else h * 1.05f
                val rgb = look.overlay and 0xFFFFFF
                overlayShader = RadialGradient(ox, oy, maxOf(w, h) * 0.6f, intArrayOf(rgb or 0xD0000000.toInt(), rgb or 0x55000000, 0), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            }
            overlayPaint.shader = overlayShader; c.drawRect(0f, 0f, w, h, overlayPaint)
        }
        // стробоскоп на ударе (клуб)
        if (look.strobe && beat > 0.3f) { strobe.alpha = (beat * 42).toInt(); c.drawRect(0f, 0f, w, h, strobe) }
        // сканлайны, как на старом экране
        val sStep = 3 * dp
        var sy = 0f; while (sy < h) { c.drawLine(0f, sy, w, sy, scan); sy += sStep }
    }

    override fun setAlpha(alpha: Int) {}

    companion object {
        /** Палитра обложки для неона: до 4 заметных оттенков (не ближе 45°) на полной яркости,
         *  не хватает — соседние к главному; серая или без обложки — малиновый, бирюзовый, фиолетовый, синий. */
        fun paletteFrom(bmp: android.graphics.Bitmap?): IntArray {
            val def = intArrayOf(Ui.NEON, Ui.GLOW, 0xFF8A2BFF.toInt(), 0xFF2B6BFF.toInt())
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
