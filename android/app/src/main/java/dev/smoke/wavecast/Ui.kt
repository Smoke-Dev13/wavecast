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
    val BG = Color.parseColor("#191627")
    val INK = Color.parseColor("#0F0D1A")
    val LILAC = Color.parseColor("#BFB0F5")
    val CORAL = Color.parseColor("#F0A08C")
    val MILK = Color.parseColor("#EFEAE2")
    val PANEL = Color.parseColor("#2A2640")
    val DIM = Color.parseColor("#9C96B0")

    /** Фирменные шрифты (как на странице слушателя), лежат в assets/fonts. */
    lateinit var display: Typeface; private set
    lateinit var medium: Typeface; private set
    lateinit var bold: Typeface; private set

    fun init(ctx: android.content.Context) {
        if (::display.isInitialized) return
        fun load(f: String, fallback: Typeface) = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/$f") }.getOrDefault(fallback)
        display = load("Unbounded-ExtraBold.ttf", Typeface.create("sans-serif-black", Typeface.NORMAL))
        medium = load("Onest-Medium.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL))
        bold = load("Onest-Bold.ttf", Typeface.DEFAULT_BOLD)
    }
}

fun Activity.px(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Activity.shape(fill: Int, radius: Int): Drawable = GradientDrawable().apply {
    setColor(fill); cornerRadius = px(radius).toFloat()
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
    background = shape(Ui.PANEL, radius)
    setPadding(px(pad), px(pad), px(pad), px(pad))
}

/** Маленькая кнопка-«таблетка» для карточек. */
fun Activity.chipButton(t: String, fill: Int, ink: Int) = Button(this).apply {
    text = t; isAllCaps = false; textSize = 14f; typeface = Ui.bold; setTextColor(ink)
    stateListAnimator = null; background = shape(fill, 99); minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
    setPadding(px(16), px(10), px(16), px(10))
    pressable(this)
}

fun Activity.field(hint: String) = EditText(this).apply {
    this.hint = hint; setSingleLine(); textSize = 16f; typeface = Ui.medium
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    setTextColor(Ui.MILK); setHintTextColor(Ui.DIM)
    background = shape(Ui.PANEL, 16)
    setPadding(px(16), px(14), px(16), px(14))
}

fun Activity.pill(fill: Int, ink: Int, size: Float) = Button(this).apply {
    isAllCaps = false; textSize = size; typeface = Ui.bold; setTextColor(ink); letterSpacing = 0f
    stateListAnimator = null; background = shape(fill, 99)
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
