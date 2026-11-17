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
                tvUpdate.text = "Установлена $cur. Вышла ${rel.versionName}" + (if (rel.notes.isNotBlank()) "\n${rel.notes}" else "")
                btnUpdate.text = "Обновить до ${rel.versionName}"
            }
            err != null -> { tvUpdate.text = "Версия $cur. Не удалось проверить: $err"; btnUpdate.text = "Проверить обновления" }
            else -> { tvUpdate.text = "Версия $cur"; btnUpdate.text = "Проверить обновления" }
        }
    }

    private fun onUpdateClick() {
        val rel = Updater.available
        if (rel == null) {
            btnUpdate.text = "Проверяю…"
            Updater.check(this, force = true) { r, err ->
                renderUpdate(r, err)
                if (r == null && err == null) tvUpdate.text = "Версия ${Updater.currentName(this)} — это последняя"
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
            setPadding(px(22), px(14), px(22), px(32))
        }
        // шапка: назад + заголовок
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("←", 26f).apply {
            setPadding(0, px(8), px(16), px(8))
            contentDescription = "Назад"
            setOnClickListener { finish() }
        })
        head.addView(label("Настройки", 26f, Ui.MILK, Ui.display))
        root.addView(head, wide())

        fun section(t: String, top: Int = 30) = root.addView(label(t, 14f, Ui.LILAC, android.graphics.Typeface.DEFAULT_BOLD), wide(top))
        fun hint(t: String) = root.addView(label(t, 13f, Ui.DIM).apply { setLineSpacing(px(2).toFloat(), 1f) }, wide(6))

        if (CaptureService.status.running) {
            root.addView(label("Эфир идёт: название и адрес поменяются в следующем эфире", 14f, Ui.MILK).apply {
                background = shape(Ui.PANEL, 16); setPadding(px(16), px(12), px(16), px(12))
            }, wide(16))
        }

        section("Эфир", 24)
        root.addView(label("Название эфира", 13f, Ui.DIM), wide(14))
        etRoom = field("party")
        root.addView(etRoom, wide(6))
        hint("Часть ссылки для друзей. Латиница, цифры, - и _")
        root.addView(label("Адрес сервиса", 13f, Ui.DIM), wide(18))
        etService = field(Settings.DEFAULT_SERVICE)
        root.addView(etService, wide(6))
        hint("Менять не нужно, если ты не поднимал свой сервис")

        section("Слушатели")
        swAuto = toggle("Пускать всех без вопросов")
        root.addView(swAuto, wide(6))
        hint("Выключено — каждый слушатель сначала просится в эфир, а ты пускаешь его кнопкой в уведомлении")

        section("Звук")
        swMute = toggle("Не транслировать мессенджеры")
        root.addView(swMute, wide(6))
        hint("Голосовые и звук Telegram, WhatsApp, Viber, Signal, VK Мессенджера, MAX не попадут в эфир")

        section("О приложении")
        tvUpdate = label("", 15f, Ui.MILK).apply { setLineSpacing(px(3).toFloat(), 1f) }
        root.addView(tvUpdate, wide(12))
        btnUpdate = pill(Ui.PANEL, Ui.MILK, 16f).apply { setOnClickListener { onUpdateClick() } }
        root.addView(btnUpdate, wide(12))
        root.addView(pill(Ui.BG, Ui.DIM, 15f).apply {
            text = "Исходный код на GitHub"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.REPO_URL))) }
        }, wide(4))

        return ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
