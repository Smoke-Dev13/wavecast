package dev.smoke.wavecast

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Общие цвета и элементы интерфейса для всех экранов. */
object Ui {
    val BG = Color.parseColor("#050302")
    val INK = Color.parseColor("#120700")
    /** Основной цвет (глаза зверя): янтарная радужка — кнопки и акценты. */
    val LILAC = Color.parseColor("#F5B700")
    val NEON = LILAC
    /** Светлая метка «в эфире» и жанр. */
    val VOLT = Color.parseColor("#FFD37A")
    val CORAL = Color.parseColor("#F0A08C")
    val MILK = Color.parseColor("#FFF4D6")
    val PANEL = Color.parseColor("#2A1E12")
    /** Полупрозрачное «стекло» поверх свечения и тонкая светлая кромка. */
    val GLASS = Color.parseColor("#B3140C06")
    val EDGE = Color.parseColor("#24FFF4D6")
    /** Свечение вокруг глаза. */
    val GLOW = Color.parseColor("#C77700")
    /** Уголки-скобы киберпанка больше не рисуем (прозрачные). */
    val BRACKET = 0
    /** Текущий цвет уголков-скоб: меняется вместе с неоном обложки. */
    var bracket = BRACKET
    val DIM = Color.parseColor("#C9B89A")

    /** Фирменные шрифты (как на странице слушателя), лежат в assets/fonts. */
    lateinit var display: Typeface; private set
    lateinit var medium: Typeface; private set
    lateinit var bold: Typeface; private set
    /** Технический шрифт для служебных надписей (как на экране осциллографа). */
    lateinit var tech: Typeface; private set
    lateinit var techSemi: Typeface; private set
    /** Тонкий крупный — для названия трека. */
    lateinit var light: Typeface; private set

    fun init(ctx: android.content.Context) {
        if (::display.isInitialized) return
        fun load(f: String, fallback: Typeface) = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/$f") }.getOrDefault(fallback)
        display = load("Unbounded-ExtraBold.ttf", Typeface.create("sans-serif-black", Typeface.NORMAL))
        medium = load("Onest-Medium.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL))
        bold = load("Onest-Bold.ttf", Typeface.DEFAULT_BOLD)
        tech = load("Jura-Bold.ttf", Typeface.DEFAULT_BOLD)
        techSemi = load("Jura-SemiBold.ttf", tech)
        light = load("Unbounded-Light.ttf", Typeface.create("sans-serif-light", Typeface.NORMAL))
    }
}

fun Activity.px(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Activity.shape(fill: Int, radius: Int): Drawable = GradientDrawable().apply {
    setColor(fill); cornerRadius = px(radius).toFloat()
}

/** Стеклянная плашка: полупрозрачная заливка и тонкая кромка. */
fun Activity.glass(radius: Int, fill: Int = Ui.GLASS): Drawable = GradientDrawable().apply {
    setColor(fill); cornerRadius = px(radius).toFloat(); setStroke(maxOf(1, px(1) / 2 + 1), Ui.EDGE)
}

/** Стеклянная плашка со скруглением (раньше — с уголками-скобами осциллографа). */
fun Activity.hud(radius: Int = 18): Drawable = HudDrawable(glass(maxOf(radius, 14)), px(11).toFloat(), maxOf(1f, resources.displayMetrics.density))

class HudDrawable(private val base: Drawable, private val arm: Float, private val sw: Float) : Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = sw
    }
    override fun onBoundsChange(b: android.graphics.Rect) { base.bounds = b }
    override fun draw(c: android.graphics.Canvas) {
        base.draw(c)
        if (Ui.bracket ushr 24 == 0) return                  // скоб нет — только стекло
        p.color = Ui.bracket                                  // уголки — второго неона (в эфире — цвет обложки)
        val b = bounds; val h = sw / 2
        val l = b.left + h; val t = b.top + h; val r = b.right - h; val bt = b.bottom - h
        c.drawLine(l, t, l + arm, t, p); c.drawLine(l, t, l, t + arm, p)
        c.drawLine(r, bt, r - arm, bt, p); c.drawLine(r, bt, r, bt - arm, p)
    }
    override fun setAlpha(a: Int) { base.alpha = a; p.alpha = a }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { base.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

fun Activity.paintBars() {
    Ui.init(this)
    window.statusBarColor = Ui.BG
    window.navigationBarColor = Ui.BG
}

/** Кнопка слегка сжимается при нажатии. */
@SuppressLint("ClickableViewAccessibility")
fun pressable(v: View) = v.setOnTouchListener { _, e ->
    val k = when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> 0.96f
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1f
        else -> return@setOnTouchListener false
    }
    v.animate().scaleX(k).scaleY(k).setDuration(70).start()
    false
}

fun Activity.label(t: String, size: Float, color: Int = Ui.MILK, face: Typeface = Ui.medium) = TextView(this).apply {
    text = t; textSize = size; setTextColor(color); typeface = face; includeFontPadding = false
}

/** Скруглённая карточка-группа. */
fun Activity.card(radius: Int = 22, pad: Int = 16) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = glass(radius)
    setPadding(px(pad), px(pad), px(pad), px(pad))
}

/** Маленькая кнопка-«таблетка» для карточек. */
fun Activity.chipButton(t: String, fill: Int, ink: Int) = Button(this).apply {
    text = t; isAllCaps = false; textSize = 14f; typeface = Ui.bold; setTextColor(ink)
    stateListAnimator = null; background = if (fill == Ui.BG || fill == Ui.PANEL) glass(99) else shape(fill, 99)
    minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
    setPadding(px(16), px(10), px(16), px(10))
    pressable(this)
}

fun Activity.field(hint: String) = EditText(this).apply {
    this.hint = hint; setSingleLine(); textSize = 16f; typeface = Ui.medium
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    setTextColor(Ui.MILK); setHintTextColor(Ui.DIM)
    background = glass(16)
    setPadding(px(16), px(14), px(16), px(14))
}

fun Activity.pill(fill: Int, ink: Int, size: Float) = Button(this).apply {
    isAllCaps = true; textSize = size - 1f; typeface = Ui.tech; setTextColor(ink); letterSpacing = 0.05f
    stateListAnimator = null; background = if (fill == Ui.BG || fill == Ui.PANEL) glass(99) else shape(fill, 99)
    setPadding(px(20), px(17), px(20), px(17))
    pressable(this)
}

fun Activity.toggle(t: String) = Switch(this).apply {
    text = t; textSize = 16f; setTextColor(Ui.MILK); typeface = Ui.medium; showText = false
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    thumbTintList = ColorStateList(states, intArrayOf(Ui.LILAC, Ui.DIM))
    trackTintList = ColorStateList(states, intArrayOf(Ui.LILAC, Ui.PANEL))
    setPadding(0, px(10), 0, px(10))
}

fun Activity.wide(top: Int = 0) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = px(top) }
