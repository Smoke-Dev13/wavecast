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
 * Сцена эфира: волна осциллографа поперёк экрана и круглая кнопка на ней.
 * В покое — сиреневый круг с ▶, волна почти ровная.
 * В эфире — стеклянный круг с ■ «СТОП», волна и свечение фона живут от громкости (CaptureService.level).
 */
class PulseButton(ctx: Context) : View(ctx) {

    var live = false
        set(v) {
            val changed = field != v
            field = v; contentDescription = if (v) "Закончить эфир" else "В эфир"
            if (changed && isAttachedToWindow && isLaidOut) animateLive(v) else { reveal = if (v) 1f else 0f }
            invalidate()
        }

    // включение эфира: луч разгоняется из центра к краям, вспышка, кольцо и короткая вибрация;
    // выключение — волна сходится обратно в центр
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
            duration = if (on) 900 else 420
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                if (on) {
                    val e = (t / 0.45f).coerceAtMost(1f)
                    reveal = 1f - (1f - e) * (1f - e)                      // быстро стартует, мягко доходит до краёв
                    flash = if (t < 0.45f) 0f else Math.min(1f, (t - 0.45f) / 0.07f) * Math.pow(((1f - t) / 0.55f).toDouble(), 1.4).toFloat()
                    if (!buzzed && t >= 0.45f) {
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

    /** Сглаженная громкость 0..1 — фон подсвечивается вместе с волной. */
    var onLevel: ((Float) -> Unit)? = null
    /** Сильная доля: громкость резко выше средней — фон вспыхивает. */
    var onBeat: (() -> Unit)? = null
    private var avgLv = 0f
    private var lastBeat = 0L

    private val dp = resources.displayMetrics.density
    private val radius = 70 * dp
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f, dp) }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.MILK; textSize = 11 * dp; textAlign = Paint.Align.CENTER; letterSpacing = 0.12f
    }
    /** Первый неон (тень волны, кольцо и заливка кнопки): малиновый, в эфире — главный цвет обложки. */
    var neonA = Ui.NEON
        set(v) { field = v; waveShadow.color = v; waveShadow.alpha = 150; neonRing.color = v; neonRing.alpha = 200 }
    /** Второй неон волны: бирюзовый, в эфире — второй цвет обложки (его задаёт главный экран). */
    var neonB = Ui.GLOW
        set(v) { field = v; waveGlow.color = v; waveGlow.alpha = 220 }
    // неоновая трубка: малиновая «тень» со сдвигом, ореол второго неона, белая сердцевина
    private val waveShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8 * dp; color = Ui.NEON; alpha = 150
        strokeJoin = Paint.Join.ROUND; maskFilter = BlurMaskFilter(9 * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val waveGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * dp; color = Ui.GLOW; alpha = 220
        strokeJoin = Paint.Join.ROUND; maskFilter = BlurMaskFilter(8 * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val neonRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8 * dp; color = Ui.NEON; alpha = 200
        maskFilter = BlurMaskFilter(12 * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val waveLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f * dp; color = Color.parseColor("#EFFBFF"); alpha = 250; strokeJoin = Paint.Join.ROUND
    }
    private val wave = Path()
    private val tri = Path()
    private val rect = RectF()
    private var smooth = 0f
    private var phase = 0f
    private var press = 1f

    init {
        isClickable = true; isFocusable = true
        contentDescription = "В эфир"
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val cx = w / 2f; val cy = height / 2f
        val target = if (live) CaptureService.level else 0f
        smooth += (target - smooth) * 0.2f
        onLevel?.invoke((smooth + flash * 0.7f).coerceAtMost(1f))
        avgLv += (smooth - avgLv) * 0.05f
        val now = android.os.SystemClock.uptimeMillis()
        if (live && smooth - avgLv > 0.05f && smooth > 0.08f && now - lastBeat > 160) { lastBeat = now; onBeat?.invoke() }
        if (live || reveal > 0f) phase += 0.07f + smooth * 0.2f

        // волна: к краям затихает, амплитуда растёт с громкостью (и на миг — от вспышки)
        val amp = height * 0.34f
        fun waveY(x: Float, base: Float, lv: Float): Float {
            val t = x / w
            val edge = Math.sin(Math.PI * t).toFloat()
            return cy + (Math.sin(t * 18.0 + phase) * Math.sin(t * 5.3 - phase * 0.4 + 1.0)).toFloat() * minOf(1.15f, base + lv * 1.25f) * amp * edge
        }
        val step = 3 * dp
        if (reveal < 1f) {                                   // в покое — тусклая почти ровная линия
            wave.reset(); var x = 0f
            while (x <= w) { val y = waveY(x, 0.035f, 0f); if (x == 0f) wave.moveTo(x, y) else wave.lineTo(x, y); x += step }
            waveLine.alpha = 110; c.drawPath(wave, waveLine); waveLine.alpha = 240
        }
        if (reveal > 0f) {
            val lv = (smooth + flash * 0.35f).coerceAtMost(1f)
            wave.reset(); var x = 0f
            while (x <= w) { val y = waveY(x, 0.07f, lv); if (x == 0f) wave.moveTo(x, y) else wave.lineTo(x, y); x += step }
            val half = reveal * w / 2f
            c.save(); c.clipRect(cx - half, 0f, cx + half, height.toFloat())
            c.save(); c.translate(0f, 4 * dp); c.drawPath(wave, waveShadow); c.restore()
            c.drawPath(wave, waveGlow); c.drawPath(wave, waveLine)
            c.restore()
            if (reveal < 1f) {                               // яркие точки на концах бегущего луча
                icon.color = Ui.MILK
                c.drawCircle(cx - half, waveY(cx - half, 0.07f, lv), 3 * dp, icon)
                c.drawCircle(cx + half, waveY(cx + half, 0.07f, lv), 3 * dp, icon)
            }
        }

        val r = radius * press
        if (flash > 0.01f) {                                 // кольцо расходится от кнопки
            ring.color = neonB; ring.alpha = (flash * 220).toInt()
            c.drawCircle(cx, cy, r * (1.05f + (1f - flash) * 0.7f), ring)
        }
        if (live) {
            // неоновое кольцо: размытый ореол + чёткая линия, внутри тёмное стекло
            c.drawCircle(cx, cy, r, neonRing)
            fill.color = 0xE007040F.toInt()
            c.drawCircle(cx, cy, r, fill)
            ring.color = neonA; ring.strokeWidth = 2 * dp
            c.drawCircle(cx, cy, r, ring)
            ring.strokeWidth = maxOf(1f, dp)
            icon.color = Ui.MILK
            val h = r * 0.17f
            rect.set(cx - h, cy - h - r * 0.08f, cx + h, cy + h - r * 0.08f)
            c.drawRoundRect(rect, h * 0.25f, h * 0.25f, icon)
            caption.typeface = Ui.tech
            c.drawText("СТОП", cx, cy + r * 0.38f, caption)
        } else {
            c.drawCircle(cx, cy, r, neonRing)
            fill.color = neonA
            c.drawCircle(cx, cy, r, fill)
            icon.color = Ui.INK
            val h = r * 0.24f
            tri.reset()                                            // ▶, сдвинут вправо для оптического центра
            tri.moveTo(cx - h * 0.62f, cy - h)
            tri.lineTo(cx + h * 0.95f, cy)
            tri.lineTo(cx - h * 0.62f, cy + h)
            tri.close()
            icon.strokeJoin = Paint.Join.ROUND; icon.strokeWidth = h * 0.18f; icon.style = Paint.Style.FILL_AND_STROKE
            c.drawPath(tri, icon)
            icon.style = Paint.Style.FILL
        }
        if (live || smooth > 0.01f || anim?.isRunning == true) postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // нажимается только сам круг, а не волна вокруг
        val inside = Math.hypot((e.x - width / 2f).toDouble(), (e.y - height / 2f).toDouble()) <= radius * 1.1
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
