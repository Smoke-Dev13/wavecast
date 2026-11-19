package dev.smoke.wavecast

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Чат эфира у ведущего: все сообщения текущего эфира, ответ от имени «Ведущий», «Отключить автора». */
class ChatActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var empty: TextView
    private var shown = 0
    private var lastSend = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paintBars()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        CaptureService.chatVisible = true
        CaptureService.chatListener = { render() }
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_CHAT_READ))
        shown = 0; list.removeAllViews()
        render()
    }

    override fun onPause() {
        CaptureService.chatVisible = false
        CaptureService.chatListener = null
        super.onPause()
    }

    private fun render() {
        val msgs = CaptureService.chat
        if (msgs.size < shown) { shown = 0; list.removeAllViews() }   // начался новый эфир
        msgs.drop(shown).forEach { list.addView(bubble(it), wide(10)) }
        shown = msgs.size
        empty.visibility = if (msgs.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (CaptureService.status.running) "Сообщений пока нет. Слушатели пишут в чат на странице эфира"
                     else "Эфир не идёт. Чат появится, когда начнёшь эфир"
        input.isEnabled = CaptureService.status.running
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(m: CaptureService.ChatMsg): View {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (m.host) shape(Ui.LILAC, 18) else glass(18)
            setPadding(px(14), px(10), px(14), px(12))
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(if (m.host) "Ты · ведущий" else m.name, 13f, if (m.host) Ui.INK else Ui.LILAC, Ui.bold).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!m.host && m.fromKey != null) {
            head.addView(label("Отключить", 12f, Ui.DIM, Ui.bold).apply {
                setPadding(px(10), px(2), 0, px(2))
                contentDescription = "Отключить ${m.name} от эфира"
                setOnClickListener {
                    startService(Intent(this@ChatActivity, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_KICK).putExtra(CaptureService.EXTRA_REQ, m.fromKey))
                    text = "Отключён"; isEnabled = false
                }
            })
        }
        b.addView(head)
        // ссылки не делаем кликабельными — обычный текст
        b.addView(label(m.text, 16f, if (m.host) Ui.INK else Ui.MILK).apply {
            setLineSpacing(px(2).toFloat(), 1f); setTextIsSelectable(true); autoLinkMask = 0
        }, wide(4))
        val wrap = LinearLayout(this).apply { gravity = if (m.host) Gravity.END else Gravity.START }
        wrap.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (m.host) marginStart = px(48) else marginEnd = px(48)
        })
        return wrap
    }

    private fun send() {
        val t = input.text.toString().trim()
        val now = System.currentTimeMillis()
        if (t.isEmpty() || !CaptureService.status.running || now - lastSend < 1000) return
        lastSend = now
        startService(Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_CHAT_SEND).putExtra(CaptureService.EXTRA_TEXT, t))
        input.setText("")
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Backdrop(this@ChatActivity).apply { glowY = 0f }
            setPadding(px(22), px(12), px(22), px(14))
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("←", 20f, Ui.MILK, Ui.bold).apply {
            gravity = Gravity.CENTER; background = glass(99); contentDescription = "Назад"
            setOnClickListener { finish() }; pressable(this)
        }, LinearLayout.LayoutParams(px(40), px(40)))
        head.addView(label("Чат эфира", 24f, Ui.MILK, Ui.tech).apply { letterSpacing = 0.02f; setPadding(px(12), 0, 0, 0) })
        root.addView(head, wide(6))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(8), 0, px(8)) }
        empty = label("", 15f, Ui.DIM).apply { gravity = Gravity.CENTER; setPadding(px(20), px(40), px(20), 0) }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(empty); inner.addView(list)
        scroll = ScrollView(this).apply { addView(inner); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(8) })

        // поле ввода + отправка
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = field("Написать всем…").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(200))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, e ->
                if (id == EditorInfo.IME_ACTION_SEND || e?.keyCode == KeyEvent.KEYCODE_ENTER) { send(); true } else false
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(chipButton("Отправить", Ui.LILAC, Ui.INK).apply {
            setPadding(px(16), px(14), px(16), px(14)); setOnClickListener { send() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8) })
        root.addView(bar, wide(8))
        return root
    }
}
