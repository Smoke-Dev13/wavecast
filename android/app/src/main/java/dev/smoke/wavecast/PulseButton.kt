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
 * Глаз зверя — кнопка эфира.
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

    /** Цвета глаза: [радужка, свечение, ободок, глаза вдали]. */
    var palette = Themes.BRAND.copyOf()
        set(v) { field = v; irisKey = 0 }
    /** Время анимации (с), его двигает главный экран: взгляд гуляет. */
    var time = 0f
    // совместимость со старым API (цвета теперь из palette)
    var neonA = Ui.NEON
    var neonB = Ui.GLOW

    private val dp = resources.displayMetrics.density
    private val eye = Path()
    private val m = android.graphics.Matrix()
    private val inside = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A0603.toInt() }
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
    private val lidLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; strokeJoin = Paint.Join.ROUND }
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

        val irisC = palette[0]; val glowC = palette[1 % palette.size]; val rimC = palette[2 % palette.size]
        // в покое глаз дремлет: щель чуть «дышит»; в эфире — открыт
        val t = time
        val sleepy = 0.16f + 0.04f * Math.sin(t * 1.3).toFloat()
        val wantOpen = sleepy + (1f - sleepy) * reveal
        open += (wantOpen - open) * 0.25f
        // моргание: раз в 3.5–8 с, 200 мс
        if (now > nextBlink) { blinkAt = now; nextBlink = now + 3500 + (Math.random() * 4500).toLong() }
        val bk = ((now - blinkAt) / 200f).let { if (it in 0f..1f) (1f - Math.sin(Math.PI * it).toFloat()).coerceAtLeast(0.05f) else 1f }
        val o = open * bk

        val a = minOf(w * 0.42f, 165 * dp) * press; halfW = a
        val hb = a * 0.5f
        val hh = maxOf(hb * o, 1.2f * dp)
        almond(cx, cy, a, hh)

        // свечение век и кольцо при включении
        val lv = (smooth + flash * 0.5f).coerceAtMost(1f)
        lidGlow.color = irisC; lidGlow.alpha = (140 + 115 * maxOf(lv, bp * 0.6f, if (live) 0.25f else 0f)).toInt().coerceAtMost(255)
        c.drawPath(eye, lidGlow)
        if (flash > 0.01f) {
            ring.color = glowC; ring.alpha = (flash * 220).toInt()
            val k = 1.05f + (1f - flash) * 0.6f
            c.save(); c.scale(k, k * 1.25f, cx, cy); c.drawPath(eye, ring); c.restore()
        }

        // глаз: тёмное нутро, радужка с волокнами, зрачок, блик
        c.save(); c.clipPath(eye)
        c.drawPath(eye, inside)
        val R = hb * 1.12f
        val ix = cx + Math.sin(t * 0.6).toFloat() * R * 0.09f; val iy = cy + Math.sin(t * 0.43 + 1).toFloat() * R * 0.05f
        val inner = android.animation.ArgbEvaluator().evaluate(0.45f, irisC, Color.WHITE) as Int
        val key = (R.toInt() * 31 + irisC) * 31 + rimC
        if (key != irisKey) {
            irisKey = key
            irisShader = android.graphics.RadialGradient(0f, 0f, R, intArrayOf(inner, inner, irisC, rimC, 0xFF0A0400.toInt()),
                floatArrayOf(0f, 0.26f, 0.62f, 0.9f, 1f), android.graphics.Shader.TileMode.CLAMP)
        }
        m.setTranslate(ix, iy); irisShader!!.setLocalMatrix(m); iris.shader = irisShader
        c.drawCircle(ix, iy, R, iris)
        for (i in 0 until 96) {
            val ang = i / 96.0 * Math.PI * 2 + (i % 3) * 0.01
            val cs = Math.cos(ang).toFloat(); val sn = Math.sin(ang).toFloat()
            val r1 = R * (0.3f + (i % 4) * 0.03f); val r2 = R * (0.86f - (i % 5) * 0.03f)
            fiber.color = if (i % 3 == 0) 0x1FFFFFFF else 0x59000000
            c.drawLine(ix + cs * r1, iy + sn * r1, ix + cs * r2, iy + sn * r2, fiber)
        }
        val dil = (lv * 1.5f + bp * 0.7f).coerceAtMost(1.2f)
        if (look.round) {
            val pr = R * (0.2f + 0.17f * dil)
            c.drawCircle(ix, iy, pr * 1.15f, pupilShade); c.drawCircle(ix, iy, pr, pupil)
        } else {
            val pw = R * (0.09f + 0.2f * dil); val ph = R * 0.82f
            rect.set(ix - pw * 1.3f, iy - ph * 1.05f, ix + pw * 1.3f, iy + ph * 1.05f); c.drawOval(rect, pupilShade)
            rect.set(ix - pw, iy - ph, ix + pw, iy + ph); c.drawOval(rect, pupil)
        }
        // тень от век сверху и снизу
        lidShade.shader = android.graphics.LinearGradient(0f, cy - hh, 0f, cy + hh, intArrayOf(0xCC000000.toInt(), 0, 0, 0x99000000.toInt()),
            floatArrayOf(0f, 0.32f, 0.72f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(cx - a, cy - hh * 1.4f, cx + a, cy + hh * 1.4f, lidShade)
        // блик, как отражение на роговице
        c.save(); c.rotate(-24f, ix - R * 0.38f, iy - R * 0.4f)
        rect.set(ix - R * 0.38f - R * 0.16f, iy - R * 0.4f - R * 0.09f, ix - R * 0.38f + R * 0.16f, iy - R * 0.4f + R * 0.09f)
        c.drawOval(rect, shine); c.restore()
        c.restore()

        lidLine.color = irisC
        c.drawPath(eye, lidLine)
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
