package dev.smoke.wavecast

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
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
        set(v) { field = v; contentDescription = if (v) "Закончить эфир" else "В эфир"; invalidate() }

    /** Сглаженная громкость 0..1 — фон подсвечивается вместе с волной. */
    var onLevel: ((Float) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val radius = 70 * dp
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f, dp) }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.MILK; textSize = 11 * dp; textAlign = Paint.Align.CENTER; letterSpacing = 0.12f
    }
    private val waveGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * dp; color = Ui.MILK; alpha = 70
        strokeJoin = Paint.Join.ROUND; maskFilter = BlurMaskFilter(6 * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val waveLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.4f * dp; color = Ui.MILK; alpha = 240; strokeJoin = Paint.Join.ROUND
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
        onLevel?.invoke(smooth)
        if (live) phase += 0.05f + smooth * 0.14f

        // волна: к краям затихает, амплитуда растёт с громкостью
        val amp = height * 0.34f
        val base = if (live) 0.07f else 0.035f
        wave.reset()
        var x = 0f
        val step = 3 * dp
        while (x <= w) {
            val t = x / w
            val edge = Math.sin(Math.PI * t).toFloat()
            val a = (base + smooth * 0.93f) * amp * edge
            val y = cy + (Math.sin(t * 18.0 + phase) * Math.sin(t * 5.3 - phase * 0.4 + 1.0)).toFloat() * a
            if (x == 0f) wave.moveTo(x, y) else wave.lineTo(x, y)
            x += step
        }
        c.drawPath(wave, waveGlow)
        c.drawPath(wave, waveLine)

        val r = radius * press
        if (live) {
            fill.color = 0xE62A2442.toInt()
            c.drawCircle(cx, cy, r, fill)
            fill.color = 0x1FEFEAE2
            c.drawCircle(cx, cy, r, fill)
            ring.color = 0x61EFEAE2
            c.drawCircle(cx, cy, r, ring)
            icon.color = Ui.MILK
            val h = r * 0.17f
            rect.set(cx - h, cy - h - r * 0.08f, cx + h, cy + h - r * 0.08f)
            c.drawRoundRect(rect, h * 0.25f, h * 0.25f, icon)
            caption.typeface = Ui.tech
            c.drawText("СТОП", cx, cy + r * 0.38f, caption)
        } else {
            fill.color = Ui.LILAC
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
        if (live || smooth > 0.01f) postInvalidateOnAnimation()
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
