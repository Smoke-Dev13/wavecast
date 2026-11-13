package dev.smoke.wavecast

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("wavecast", MODE_PRIVATE) }

    private lateinit var cbLocal: CheckBox
    private lateinit var etPort: EditText
    private lateinit var cbRelay: CheckBox
    private lateinit var etUrl: EditText
    private lateinit var etKey: EditText
    private lateinit var btn: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        cbLocal.isChecked = prefs.getBoolean("local", true)
        etPort.setText(prefs.getInt("port", 8080).toString())
        cbRelay.isChecked = prefs.getBoolean("relay", false)
        etUrl.setText(prefs.getString("url", ""))
        etKey.setText(prefs.getString("key", ""))
        btn.setOnClickListener { if (CaptureService.status.running) stop() else askPermissions() }
    }

    override fun onResume() {
        super.onResume()
        CaptureService.listener = { render(it) }
        render(CaptureService.status)
    }

    override fun onPause() {
        CaptureService.listener = null
        super.onPause()
    }

    // ---- запуск ----

    private fun askPermissions() {
        if (!cbLocal.isChecked && !cbRelay.isChecked) { toast("Включи хотя бы один режим"); return }
        if (cbRelay.isChecked && etUrl.text.isBlank()) { toast("Укажи адрес relay-сервера"); return }
        savePrefs()
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) askProjection() else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askProjection()
        else toast("Без доступа к записи звука захват невозможен")
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) { toast("Трансляция отменена"); return }
        val i = Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data)
            .putExtra(CaptureService.EXTRA_LOCAL, cbLocal.isChecked)
            .putExtra(CaptureService.EXTRA_PORT, port())
            .putExtra(CaptureService.EXTRA_RELAY_URL, if (cbRelay.isChecked) etUrl.text.toString() else "")
            .putExtra(CaptureService.EXTRA_RELAY_KEY, etKey.text.toString())
        startForegroundService(i)
    }

    private fun stop() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
    }

    private fun port() = etPort.text.toString().toIntOrNull()?.takeIf { it in 1024..65535 } ?: 8080

    private fun savePrefs() = prefs.edit()
        .putBoolean("local", cbLocal.isChecked).putInt("port", port())
        .putBoolean("relay", cbRelay.isChecked)
        .putString("url", etUrl.text.toString().trim()).putString("key", etKey.text.toString())
        .apply()

    // ---- UI ----

    private fun render(s: CaptureService.Status) {
        val on = s.running
        btn.text = if (on) "■  Остановить" else "▶  Начать трансляцию"
        btn.setBackgroundColor(if (on) Color.parseColor("#2A3440") else ACCENT)
        btn.setTextColor(if (on) Color.WHITE else Color.parseColor("#06210F"))
        listOf(cbLocal, etPort, cbRelay, etUrl, etKey).forEach { it.isEnabled = !on }

        val sb = StringBuilder()
        when {
            s.error != null -> sb.append("⚠ ${s.error}\n")
            !on -> sb.append("Включи трек в любом приложении и нажми «Начать».\n" +
                    "Звук приложений, запретивших захват, передаваться не будет.\n")
        }
        if (on) {
            sb.append("● В ЭФИРЕ\n\n")
            if (s.localUrls.isNotEmpty()) {
                sb.append("Wi-Fi / точка доступа (слушателей: ${s.localListeners}):\n")
                s.localUrls.forEach { sb.append("   $it\n") }
                sb.append("\n")
            } else if (cbLocal.isChecked) sb.append("Wi-Fi: нет локальной сети\n\n")
            if (s.relayEnabled) {
                sb.append("Relay: ")
                sb.append(if (s.relayConnected) "подключено, слушателей: ${s.relayListeners}" else "переподключение… ${s.relayError ?: ""}")
                sb.append("\n   ${etUrl.text.toString().trim().trimEnd('/')}\n")
            }
            sb.append("\nНажми на текст, чтобы скопировать ссылку.")
        }
        tvStatus.text = sb.toString()
    }

    private fun buildUi(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(28), px(20), px(20))
            setBackgroundColor(Color.parseColor("#0B0F14"))
        }
        fun label(t: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.parseColor("#E6EDF3"))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, px(6), 0, px(6))
        }
        fun field(hint: String, type: Int) = EditText(this).apply {
            this.hint = hint; inputType = type; setSingleLine()
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#5B6875"))
        }

        root.addView(label("WaveCast", 26f, true))
        root.addView(label("Транслирует звук телефона друзьям в браузер").apply { setTextColor(Color.parseColor("#8B98A5")) })

        cbLocal = CheckBox(this).apply { text = "Локально (Wi-Fi / точка доступа)"; setTextColor(Color.WHITE) }
        etPort = field("Порт, по умолчанию 8080", InputType.TYPE_CLASS_NUMBER)
        cbRelay = CheckBox(this).apply { text = "Через интернет (relay-сервер)"; setTextColor(Color.WHITE) }
        etUrl = field("https://cast.example.com/r/party", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        etKey = field("Ключ трансляции (WAVECAST_KEY)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        listOf(cbLocal, etPort, cbRelay, etUrl, etKey).forEach { root.addView(it) }

        btn = Button(this).apply {
            textSize = 18f; isAllCaps = false
            setPadding(0, px(14), 0, px(14))
        }
        root.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(20); bottomMargin = px(16)
        })

        tvStatus = label("").apply {
            gravity = Gravity.START
            setTextIsSelectable(false)
            setOnClickListener { copyLink() }
        }
        root.addView(tvStatus)

        return ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0B0F14")); addView(root) }
    }

    private fun copyLink() {
        val s = CaptureService.status
        val link = s.localUrls.firstOrNull()
            ?: etUrl.text.toString().trim().takeIf { s.relayEnabled && it.isNotEmpty() }
            ?: return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("WaveCast", link))
        toast("Скопировано: $link")
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_PROJECTION = 2
        private val ACCENT = Color.parseColor("#4ADE80")
    }
}
