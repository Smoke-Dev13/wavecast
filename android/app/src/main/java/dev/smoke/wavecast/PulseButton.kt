package dev.smoke.wavecast

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Кибер-глаз (имплант в духе киберпанка) — кнопка эфира.
 * В покое глаз почти закрыт и дремлет (узкая светящаяся щель), нажали — открывается.
 * В эфире радужка смотрит по сторонам, зрачок расширяется от громкости и на сильных долях,
 * глаз иногда моргает. Цвета и форма зрачка — по жанру (Themes).
 */
class PulseButton(ctx: Context) : View(ctx) {

    var live = false
        set(v) {
            val changed = field != v
            field = v; contentDescription = if (v) "Закончить эфир" else "В эфир"
            if (changed && isAttachedToWindow && isLaidOut) animateLive(v) else { reveal = if (v) 1f else 0f }
            invalidate()
        }

    // включение эфира: глаз распахивается, вспышка и кольцо расходятся, короткая вибрация; выключение — глаз закрывается
    private var reveal = 0f
    private var flash = 0f
    private var anim: android.animation.ValueAnimator? = null
    private fun animateLive(on: Boolean) {
        anim?.cancel()
        if (android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) {
            reveal = if (on) 1f else 0f; flash = 0f; return
        }
        var buzzed = false
        val from = reveal
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (on) 900 else 500
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                if (on) {
                    val e = (t / 0.4f).coerceAtMost(1f)
                    reveal = 1f - (1f - e) * (1f - e) * (1f - e)
                    flash = if (t < 0.35f) 0f else Math.min(1f, (t - 0.35f) / 0.07f) * Math.pow(((1f - t) / 0.65f).toDouble(), 1.4).toFloat()
                    if (!buzzed && t >= 0.35f) {
                        buzzed = true
                        performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM
                                              else android.view.HapticFeedbackConstants.LONG_PRESS)
                    }
                } else { reveal = from * (1f - t) * (1f - t); flash = 0f }
                invalidate()
            }
            start()
        }
    }

    /** Сглаженная громкость 0..1 — фон подсвечивается вместе с глазом. */
    var onLevel: ((Float) -> Unit)? = null
    /** Сильная доля: громкость резко выше средней — зрачок дёргается, фон вспыхивает. */
    var onBeat: (() -> Unit)? = null
    /** Тема по жанру: когда вспыхивать и какой зрачок (щель или круглый). */
    var look = Themes.DEFAULT
    private var avgLv = 0f
    private var lastBeat = 0L
    private var bp = 0f

    /** Первый неон (контур глаза, внешнее кольцо): переливается по палитре жанра, его задаёт главный экран. */
    var neonA = Ui.NEON
    /** Второй неон (пластины радужки, скан, глитч). */
    var neonB = Ui.GLOW
    private val t0 = android.os.SystemClock.uptimeMillis()

    private val dp = resources.displayMetrics.density
    private val eye = Path()
    private val eye2 = Path()
    private val hudP = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = maxOf(1f, 1.3f * resources.displayMetrics.density) }
    private val m = android.graphics.Matrix()
    private val inside = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE007040F.toInt() }
    private val iris = Paint(Paint.ANTI_ALIAS_FLAG)
    private var irisShader: android.graphics.RadialGradient? = null
    private var irisKey = 0
    private val fiber = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = maxOf(1f, dp * 0.8f); strokeCap = Paint.Cap.ROUND }
    private val pupil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val pupilShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0000000.toInt(); maskFilter = BlurMaskFilter(6 * dp, BlurMaskFilter.Blur.NORMAL) }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9FFFFFF.toInt(); maskFilter = BlurMaskFilter(2.5f * dp, BlurMaskFilter.Blur.NORMAL) }
    private val lidShade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lidGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7 * dp; strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(12 * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val lidLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; strokeJoin = Paint.Join.MITER }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 16 * resources.displayMetrics.density; strokeJoin = Paint.Join.MITER
        maskFilter = BlurMaskFilter(26 * resources.displayMetrics.density, BlurMaskFilter.Blur.NORMAL)
    }
    private val ringGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; maskFilter = BlurMaskFilter(7 * resources.displayMetrics.density, BlurMaskFilter.Blur.NORMAL)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp }
    private val rect = RectF()
    private var smooth = 0f
    private var press = 1f
    private var open = 0.16f
    private var blinkAt = 0L
    private var nextBlink = android.os.SystemClock.uptimeMillis() + 3000
    private var halfW = 0f

    init {
        isClickable = true; isFocusable = true
        contentDescription = "В эфир"
    }

    /** Угловатый контур импланта: пластины со скосами, а не плавный миндаль. */
    private fun cyber(cx: Float, cy: Float, a: Float, hh: Float) {
        eye.reset()
        eye.moveTo(cx - a, cy)
        eye.lineTo(cx - a * 0.62f, cy - hh * 0.78f)
        eye.lineTo(cx - a * 0.36f, cy - hh)
        eye.lineTo(cx + a * 0.3f, cy - hh)
        eye.lineTo(cx + a * 0.42f, cy - hh * 0.86f)
        eye.lineTo(cx + a * 0.7f, cy - hh * 0.62f)
        eye.lineTo(cx + a, cy)
        eye.lineTo(cx + a * 0.6f, cy + hh * 0.8f)
        eye.lineTo(cx + a * 0.34f, cy + hh)
        eye.lineTo(cx - a * 0.44f, cy + hh)
        eye.lineTo(cx - a * 0.72f, cy + hh * 0.56f)
        eye.close()
    }

    private fun almond(cx: Float, cy: Float, a: Float, hh: Float) {
        eye.reset()
        eye.moveTo(cx - a, cy)
        eye.cubicTo(cx - a * 0.45f, cy - hh * 1.33f, cx + a * 0.45f, cy - hh * 1.33f, cx + a, cy)
        eye.cubicTo(cx + a * 0.45f, cy + hh * 1.33f, cx - a * 0.45f, cy + hh * 1.33f, cx - a, cy)
        eye.close()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val cx = w / 2f; val cy = height / 2f
        val now = android.os.SystemClock.uptimeMillis()
        val target = if (live) CaptureService.level else 0f
        smooth += (target - smooth) * 0.2f
        onLevel?.invoke((smooth + flash * 0.7f).coerceAtMost(1f))
        avgLv += (smooth - avgLv) * 0.05f
        if (live && look.beat > 0f && smooth - avgLv > look.thr && smooth > 0.08f && now - lastBeat > look.gap) { lastBeat = now; bp = 1f; onBeat?.invoke() }
        bp *= 0.86f

        val mainC = neonA; val glowC = neonB; val redC = 0xFFFF003C.toInt(); val altC = Ui.VOLT
        // в покое имплант «спит»: щель чуть дышит; в эфире — открыт
        val t = (now - t0) / 1000f
        val sleepy = 0.16f + 0.04f * Math.sin(t * 1.3).toFloat()
        val wantOpen = sleepy + (1f - sleepy) * reveal
        open += (wantOpen - open) * 0.25f
        if (now > nextBlink) { blinkAt = now; nextBlink = now + 4000 + (Math.random() * 5000).toLong() }
        val bk = ((now - blinkAt) / 160f).let { if (it in 0f..1f) (1f - Math.sin(Math.PI * it).toFloat()).coerceAtLeast(0.05f) else 1f }
        val o = open * bk

        val a = minOf(w * 0.42f, 165 * dp) * press; halfW = a
        val hb = a * 0.5f
        val hh = maxOf(hb * o, 1.2f * dp)
        cyber(cx, cy, a, hh)
        val lv = (smooth + flash * 0.5f).coerceAtMost(1f)
        val glitch = if (live) bp else 0f

        // глитч на ударе: контур расслаивается на красную и голубую копии
        if (glitch > 0.25f) {
            val d = 5 * dp * glitch
            lidLine.alpha = (200 * glitch).toInt()
            c.save(); c.translate(-d, 0f); lidLine.color = redC; lidLine.alpha = (220 * glitch).toInt(); c.drawPath(eye, lidLine); c.restore()
            c.save(); c.translate(d, d * 0.3f); lidLine.color = glowC; lidLine.alpha = (220 * glitch).toInt(); c.drawPath(eye, lidLine); c.restore()
            lidLine.alpha = 255
        }
        // неоновый ореол в два слоя, как у кольца кнопки раньше; на ударе вспыхивает
        val hot = maxOf(lv, bp, if (live) 0.35f else 0.2f)
        halo.color = mainC; halo.alpha = (90 + 140 * hot).toInt().coerceAtMost(255); c.drawPath(eye, halo)
        lidGlow.color = mainC; lidGlow.alpha = (170 + 85 * hot).toInt().coerceAtMost(255)
        c.drawPath(eye, lidGlow)
        if (flash > 0.01f) {
            ring.color = glowC; ring.alpha = (flash * 220).toInt()
            val k = 1.05f + (1f - flash) * 0.6f
            c.save(); c.scale(k, k * 1.25f, cx, cy); c.drawPath(eye, ring); c.restore()
        }

        // нутро: тёмное стекло, кольца сканера, диафрагма-зрачок
        c.save(); c.clipPath(eye)
        c.drawPath(eye, inside)
        val R = hb * 1.05f
        val ix = cx + Math.sin(t * 0.6).toFloat() * R * 0.07f; val iy = cy + Math.sin(t * 0.43 + 1).toFloat() * R * 0.04f
        val kick = bp * 25f
        // внешнее кольцо: 12 штрихов, крутится по часовой
        ring.strokeWidth = 3 * dp; ring.color = mainC; ring.alpha = 230
        rect.set(ix - R * 0.92f, iy - R * 0.92f, ix + R * 0.92f, iy + R * 0.92f)
        val r1 = t * 22f + kick
        ringGlow.color = mainC; ringGlow.strokeWidth = 7 * dp; ringGlow.alpha = (160 + 90 * hot).toInt().coerceAtMost(255)
        for (k in 0 until 12) c.drawArc(rect, r1 + k * 30f, 19f, false, ringGlow)
        for (k in 0 until 12) c.drawArc(rect, r1 + k * 30f, 19f, false, ring)
        // широкое кольцо: 5 пластин второго цвета, против часовой
        ring.strokeWidth = R * 0.13f; ring.color = glowC; ring.alpha = 200
        rect.set(ix - R * 0.72f, iy - R * 0.72f, ix + R * 0.72f, iy + R * 0.72f)
        val r2 = -t * 14f - kick * 1.6f
        ringGlow.color = glowC; ringGlow.strokeWidth = R * 0.2f; ringGlow.alpha = (120 + 100 * hot).toInt().coerceAtMost(255)
        for (k in 0 until 5) c.drawArc(rect, r2 + k * 72f, 52f, false, ringGlow)
        for (k in 0 until 5) c.drawArc(rect, r2 + k * 72f, 52f, false, ring)
        // красная метка-дуга
        ring.strokeWidth = 2 * dp; ring.color = redC; ring.alpha = 255
        rect.set(ix - R * 0.56f, iy - R * 0.56f, ix + R * 0.56f, iy + R * 0.56f)
        c.drawArc(rect, t * 40f, 40f, false, ring)
        // засечки
        fiber.color = mainC; fiber.alpha = 150
        for (k in 0 until 36) {
            val ang = Math.toRadians((k * 10 + t * 8).toDouble()); val cs = Math.cos(ang).toFloat(); val sn = Math.sin(ang).toFloat()
            val ra = R * 0.5f; val rb = R * (if (k % 3 == 0) 0.44f else 0.47f)
            c.drawLine(ix + cs * ra, iy + sn * ra, ix + cs * rb, iy + sn * rb, fiber)
        }
        ring.strokeWidth = 2 * dp
        // диафрагма: тёмный диск, шестигранное отверстие раскрывается от громкости
        val dil = (lv * 1.5f + bp * 0.7f).coerceAtMost(1.2f)
        val dr = R * 0.4f
        iris.shader = null; iris.color = 0xFF14161E.toInt(); c.drawCircle(ix, iy, dr, iris)
        val ap = R * (0.1f + 0.22f * dil); val rot = Math.toRadians((dil * 40 + t * 6).toDouble())
        eye2.reset()
        for (k in 0 until 6) {
            val ang = rot + k * Math.PI / 3
            val px = ix + Math.cos(ang).toFloat() * ap; val py = iy + Math.sin(ang).toFloat() * ap
            if (k == 0) eye2.moveTo(px, py) else eye2.lineTo(px, py)
        }
        eye2.close(); c.drawPath(eye2, pupil)
        fiber.color = 0xFF3A3F4F.toInt(); fiber.alpha = 255
        for (k in 0 until 6) {                                     // лепестки диафрагмы
            val ang = rot + k * Math.PI / 3
            val px = ix + Math.cos(ang).toFloat() * ap; val py = iy + Math.sin(ang).toFloat() * ap
            val ang2 = ang + Math.PI / 2.2
            c.drawLine(px, py, ix + Math.cos(ang2).toFloat() * dr, iy + Math.sin(ang2).toFloat() * dr, fiber)
        }
        iris.color = redC; c.drawCircle(ix, iy, maxOf(1.6f * dp, ap * 0.18f), iris)       // огонёк камеры
        // сканирующая полоса раз в ~3 с
        val sc = (t % 3.2f) / 0.7f
        if (sc in 0f..1f && o > 0.3f) {
            val sy = cy - hh + sc * hh * 2
            ring.color = glowC; ring.alpha = 200; ring.strokeWidth = 1.5f * dp
            c.drawLine(cx - a, sy, cx + a, sy, ring)
            lidShade.shader = null; lidShade.color = (glowC and 0xFFFFFF) or 0x22000000
            c.drawRect(cx - a, sy - 10 * dp, cx + a, sy, lidShade)
        }
        // тень сверху и снизу
        lidShade.color = Color.BLACK
        lidShade.shader = android.graphics.LinearGradient(0f, cy - hh, 0f, cy + hh, intArrayOf(0xB0000000.toInt(), 0, 0, 0x90000000.toInt()),
            floatArrayOf(0f, 0.25f, 0.75f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(cx - a, cy - hh * 1.4f, cx + a, cy + hh * 1.4f, lidShade)
        c.restore()

        lidLine.color = mainC; lidLine.alpha = 255
        c.drawPath(eye, lidLine)
        // HUD вокруг: уголки-скобы по краям и строка засечек снизу
        hudP.color = altC; hudP.alpha = 210
        val ex = a + 14 * dp; val ey = maxOf(hh, 18 * dp) + 6 * dp
        c.drawLine(cx - ex, cy - ey, cx - ex + 14 * dp, cy - ey, hudP); c.drawLine(cx - ex, cy - ey, cx - ex, cy - ey + 10 * dp, hudP)
        c.drawLine(cx + ex, cy + ey, cx + ex - 14 * dp, cy + ey, hudP); c.drawLine(cx + ex, cy + ey, cx + ex, cy + ey - 10 * dp, hudP)
        hudP.alpha = 140
        var tx = cx - a * 0.5f; var n = 0
        while (tx <= cx + a * 0.5f) { val th = if (n % 5 == 0) 6 * dp else 3 * dp; c.drawLine(tx, cy + ey + 6 * dp, tx, cy + ey + 6 * dp + th, hudP); tx += 5 * dp; n++ }
        // глитч: горизонтальный срез контура сдвигается вбок
        if (glitch > 0.4f) {
            val band = cy - hh + (Math.random() * hh * 2).toFloat(); val bh = 6 * dp
            c.save(); c.clipRect(cx - a - 20 * dp, band, cx + a + 20 * dp, band + bh)
            c.translate(((Math.random() - 0.5) * 24 * dp).toFloat(), 0f); lidLine.color = redC; c.drawPath(eye, lidLine); c.restore()
        }
        postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // нажимается только сам глаз, а не пустое место вокруг
        val dx = (e.x - width / 2f) / maxOf(halfW, 60 * dp); val dy = (e.y - height / 2f) / (70 * dp)
        val inside = dx * dx + dy * dy <= 1.2f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { if (!inside) return false; press = 0.95f; invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { press = 1f; invalidate() }
        }
        return super.onTouchEvent(e)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
    }
}
