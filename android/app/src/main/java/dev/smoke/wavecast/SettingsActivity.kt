package dev.smoke.wavecast

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private val s by lazy { Settings(this) }
    private lateinit var etRoom: EditText
    private lateinit var etService: EditText
    private lateinit var swAuto: Switch
    private lateinit var swMute: Switch
    private lateinit var tvUpdate: TextView
    private lateinit var btnUpdate: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paintBars()
        setContentView(buildUi())
        etRoom.setText(s.room)
        etService.setText(s.service)
        swAuto.isChecked = s.autoAllow
        swMute.isChecked = s.muteMessengers
        swAuto.setOnCheckedChangeListener { _, on ->
            s.autoAllow = on
            if (CaptureService.status.running) startService(Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_AUTO).putExtra(CaptureService.EXTRA_AUTO, on))
        }
        swMute.setOnCheckedChangeListener { _, on -> s.muteMessengers = on }
        renderUpdate(Updater.available, null)
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun save() {
        val room = etRoom.text.toString().trim().lowercase()
        if (Settings.ROOM_RE.matches(room)) s.room = room
        else if (room.isNotEmpty()) toast("Название эфира не сохранено: только латиница, цифры, - и _")
        s.service = etService.text.toString()
        etService.setText(s.service)
    }

    private fun renderUpdate(rel: Updater.Release?, err: String?) {
        val cur = Updater.currentName(this)
        when {
            rel != null -> {
                tvUpdate.text = "Версия $cur\nВышла ${rel.versionName}" + (if (rel.notes.isNotBlank()) ": ${rel.notes}" else "")
                btnUpdate.text = "Обновить"
            }
            err != null -> { tvUpdate.text = "Версия $cur\nНе удалось проверить: $err"; btnUpdate.text = "Проверить" }
            else -> { tvUpdate.text = "Версия $cur"; btnUpdate.text = "Проверить" }
        }
    }

    private fun onUpdateClick() {
        val rel = Updater.available
        if (rel == null) {
            btnUpdate.text = "Проверяю…"
            Updater.check(this, force = true) { r, err ->
                renderUpdate(r, err)
                if (r == null && err == null) tvUpdate.text = "Версия ${Updater.currentName(this)}\nЭто последняя версия"
            }
            return
        }
        if (CaptureService.status.running) { toast("Сначала закончи эфир — при обновлении приложение перезапустится"); return }
        if (!Updater.ensureInstallAllowed(this)) { toast("Разреши WaveCast устанавливать обновления и нажми ещё раз"); return }
        btnUpdate.isEnabled = false
        Updater.downloadAndInstall(this, rel) { text, finished ->
            btnUpdate.text = text ?: "Обновить"
            if (finished) btnUpdate.isEnabled = true
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(12), px(22), px(32))
        }
        // шапка: круглая «назад» + заголовок
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("←", 20f, Ui.MILK, Ui.bold).apply {
            gravity = Gravity.CENTER; background = shape(Ui.PANEL, 99)
            contentDescription = "Назад"
            setOnClickListener { finish() }
            pressable(this)
        }, LinearLayout.LayoutParams(px(40), px(40)))
        head.addView(label("Настройки", 24f, Ui.MILK, Ui.display).apply { letterSpacing = -0.03f; setPadding(px(12), 0, 0, 0) })
        root.addView(head, wide(6))

        if (CaptureService.status.running) {
            root.addView(label("Эфир идёт: название и адрес поменяются в следующем эфире", 14f, Ui.MILK).apply {
                background = shape(Ui.PANEL, 16); setPadding(px(16), px(12), px(16), px(12))
            }, wide(18))
        }

        fun section(t: String) = root.addView(label(t, 13f, Ui.DIM).apply { setPadding(px(4), 0, 0, 0) }, wide(24))
        fun group() = card(20, 0).also { root.addView(it, wide(8)) }
        fun divider(g: LinearLayout) = g.addView(View(this).apply { setBackgroundColor(LINE) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        fun inputRow(g: LinearLayout, title: String, hint: String, help: String): EditText {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(14), px(16), px(12)) }
            row.addView(label(title, 13f, Ui.DIM))
            val e = field(hint).apply { background = null; setPadding(0, px(6), 0, px(4)); textSize = 16f }
            row.addView(e, wide())
            row.addView(label(help, 12f, Ui.DIM).apply { alpha = 0.8f; setLineSpacing(px(2).toFloat(), 1f) }, wide(2))
            g.addView(row)
            return e
        }
        fun toggleRow(g: LinearLayout, title: String, help: String): Switch {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), px(14), px(12), px(14)) }
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(label(title, 16f, Ui.MILK))
            txt.addView(label(help, 13f, Ui.DIM).apply { setLineSpacing(px(2).toFloat(), 1f) }, wide(4))
            row.addView(txt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val sw = toggle("").apply { setPadding(px(10), 0, 0, 0) }
            row.addView(sw)
            row.setOnClickListener { sw.toggle() }
            g.addView(row)
            return sw
        }

        section("Эфир")
        val g1 = group()
        etRoom = inputRow(g1, "Название эфира", "party", "Часть ссылки для друзей. Латиница, цифры, - и _")
        divider(g1)
        etService = inputRow(g1, "Адрес сервиса", Settings.DEFAULT_SERVICE, "Менять не нужно, если ты не поднимал свой сервис")

        section("Слушатели и звук")
        val g2 = group()
        swAuto = toggleRow(g2, "Пускать всех без вопросов", "Иначе каждый просится в эфир, а ты пускаешь его кнопкой в уведомлении")
        divider(g2)
        swMute = toggleRow(g2, "Не транслировать мессенджеры", "Голосовые и звук Telegram, WhatsApp, Viber, Signal, VK Мессенджера, MAX не попадут в эфир")

        section("О приложении")
        val g3 = group()
        val ver = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), px(14), px(12), px(14)) }
        tvUpdate = label("", 15f, Ui.MILK).apply { setLineSpacing(px(3).toFloat(), 1f) }
        ver.addView(tvUpdate, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnUpdate = chipButton("Проверить", Ui.BG, Ui.MILK).apply { setOnClickListener { onUpdateClick() } }
        ver.addView(btnUpdate, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(10) })
        g3.addView(ver)
        divider(g3)
        g3.addView(label("Исходный код на GitHub", 16f, Ui.MILK).apply {
            setPadding(px(16), px(16), px(16), px(16))
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.REPO_URL))) }
        })

        return ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) }
    }

    companion object { private val LINE = android.graphics.Color.parseColor("#36314F") }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
