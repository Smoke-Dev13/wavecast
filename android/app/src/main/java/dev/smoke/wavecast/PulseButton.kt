package dev.smoke.wavecast

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Большая круглая кнопка эфира. В покое — сиреневая с ▶ и мягким ореолом;
 * в эфире — светлая с ■, ореол дышит в такт громкости (CaptureService.level).
 */
class PulseButton(ctx: Context) : View(ctx) {

    var live = false
        set(v) { field = v; contentDescription = if (v) "Закончить эфир" else "В эфир"; invalidate() }

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.INK }
    private val tri = Path()
    private val rect = RectF()
    private var smooth = 0f
    private var press = 1f

    init {
        isClickable = true; isFocusable = true
        contentDescription = "В эфир"
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f / 1.55f * press          // круг ≈ 65% от размера вида, остальное — ореол
        val target = if (live) CaptureService.level else 0f
        smooth += (target - smooth) * 0.25f

        // ореол: два кольца; в эфире расширяются под музыку (не выходят за границы вида)
        val room = minOf(width, height) / 2f - r
        halo.color = Ui.LILAC
        halo.alpha = if (live) 26 else 16
        c.drawCircle(cx, cy, r + minOf(room, (30 + 22 * smooth) * dp), halo)
        halo.alpha = if (live) 46 else 30
        c.drawCircle(cx, cy, r + minOf(room, (14 + 14 * smooth) * dp), halo)

        fill.color = if (live) Ui.MILK else Ui.LILAC
        c.drawCircle(cx, cy, r, fill)

        if (live) {
            val h = r * 0.30f
            rect.set(cx - h, cy - h, cx + h, cy + h)
            c.drawRoundRect(rect, h * 0.28f, h * 0.28f, icon)
        } else {
            val h = r * 0.36f
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
        // нажимается только сам круг, а не прозрачный ореол вокруг
        val r = minOf(width, height) / 2f / 1.55f
        val inside = Math.hypot((e.x - width / 2f).toDouble(), (e.y - height / 2f).toDouble()) <= r * 1.1
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
