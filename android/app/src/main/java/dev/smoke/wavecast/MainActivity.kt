package dev.smoke.wavecast

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.security.SecureRandom

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("wavecast", MODE_PRIVATE) }

    private lateinit var etService: EditText
    private lateinit var etRoom: EditText
    private lateinit var btn: Button
    private lateinit var btnShare: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLink: TextView
    private lateinit var ivQr: ImageView
    private var qrFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        etService.setText(prefs.getString("service", ""))
        etRoom.setText(prefs.getString("room", null) ?: random(ROOM_CHARS, 6))
        btn.setOnClickListener { if (CaptureService.status.running) stop() else askPermissions() }
        btnShare.setOnClickListener { share() }
        tvLink.setOnClickListener { copyLink() }
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

    private fun room() = etRoom.text.toString().trim().lowercase()

    private fun hostKey(): String =
        prefs.getString("hostKey", null) ?: random(KEY_CHARS, 32).also { prefs.edit().putString("hostKey", it).apply() }

    private fun askPermissions() {
        if (etService.text.isNotBlank() && !ROOM_RE.matches(room())) {
            toast("Название эфира: латиница, цифры, - и _"); return
        }
        prefs.edit().putString("service", etService.text.toString().trim()).putString("room", room()).apply()
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
            .putExtra(CaptureService.EXTRA_SERVICE, etService.text.toString().trim())
            .putExtra(CaptureService.EXTRA_ROOM, room())
            .putExtra(CaptureService.EXTRA_HOST_KEY, hostKey())
        startForegroundService(i)
    }

    private fun stop() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
    }

    // ---- UI ----

    private fun link(s: CaptureService.Status) = s.p2pUrl ?: s.localUrls.firstOrNull()

    private fun render(s: CaptureService.Status) {
        val on = s.running
        btn.text = if (on) "■  Остановить эфир" else "▶  В эфир"
        btn.setBackgroundColor(if (on) Color.parseColor("#2A3440") else ACCENT)
        btn.setTextColor(if (on) Color.WHITE else Color.parseColor("#06210F"))
        etService.isEnabled = !on
        etRoom.isEnabled = !on

        val url = if (on) link(s) else null
        listOf(tvLink, ivQr, btnShare).forEach { it.visibility = if (url != null) View.VISIBLE else View.GONE }
        if (url != null) {
            tvLink.text = url
            if (qrFor != url) { ivQr.setImageBitmap(qr(url)); qrFor = url }
        }

        val sb = StringBuilder()
        if (s.error != null) sb.append("⚠ ${s.error}\n\n")
        if (!on) {
            sb.append("Нажми «В эфир», потом включай музыку в любом приложении — друзья услышат то же, что и ты.\n\n")
            sb.append(if (etService.text.isBlank()) "Без адреса сервиса эфир доступен только в твоей Wi-Fi сети / точке доступа."
                      else "Друзья подключаются по ссылке или QR-коду, из любой сети.")
        } else {
            sb.append("● В ЭФИРЕ\n\n")
            if (s.p2pUrl != null) {
                sb.append("По ссылке: слушателей ${s.p2pListeners}")
                if (!s.p2pConnected) sb.append("\n⚠ ${s.p2pError ?: "подключаюсь к сервису…"}")
                sb.append("\n\n")
            }
            if (s.localUrls.isNotEmpty()) {
                sb.append("В твоей Wi-Fi сети, без интернета (слушателей ${s.localListeners}):\n")
                s.localUrls.forEach { sb.append("   $it\n") }
            } else if (s.p2pUrl == null) sb.append("Нет Wi-Fi сети: подключись к Wi-Fi или включи точку доступа.\n")
        }
        tvStatus.text = sb.toString()
    }

    private fun buildUi(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(28), px(20), px(20))
        }
        fun label(t: String, size: Float = 14f, bold: Boolean = false, color: String = "#E6EDF3") = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.parseColor(color))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, px(6), 0, px(6))
        }
        fun field(hint: String, type: Int) = EditText(this).apply {
            this.hint = hint; inputType = type; setSingleLine()
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#5B6875"))
        }
        fun wide(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(top); bottomMargin = px(bottom) }

        root.addView(label("WaveCast", 26f, true))
        root.addView(label("Твоё радио: друзья слышат то, что играет у тебя", color = "#8B98A5"))

        root.addView(label("Адрес сервиса", 12f, color = "#8B98A5"))
        etService = field("wavecast.твой-аккаунт.workers.dev", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        root.addView(etService)
        root.addView(label("Название эфира", 12f, color = "#8B98A5"))
        etRoom = field("party", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        root.addView(etRoom)

        btn = Button(this).apply { textSize = 18f; isAllCaps = false; setPadding(0, px(14), 0, px(14)) }
        root.addView(btn, wide(top = 18, bottom = 14))

        ivQr = ImageView(this).apply { visibility = View.GONE }
        root.addView(ivQr, LinearLayout.LayoutParams(px(220), px(220)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        tvLink = label("", 15f, true, "#4ADE80").apply { gravity = Gravity.CENTER; visibility = View.GONE }
        root.addView(tvLink, wide(top = 8))
        btnShare = Button(this).apply { text = "Поделиться ссылкой"; isAllCaps = false; visibility = View.GONE }
        root.addView(btnShare, wide(bottom = 10))

        tvStatus = label("")
        root.addView(tvStatus)

        return ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0B0F14")); addView(root) }
    }

    private fun qr(text: String): Bitmap {
        val n = 480
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, n, n)
        val px = IntArray(n * n) { if (m.get(it % n, it / n)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.RGB_565)
    }

    private fun share() {
        val url = link(CaptureService.status) ?: return
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Слушай мой эфир в WaveCast: $url")
        startActivity(Intent.createChooser(i, "Поделиться эфиром"))
    }

    private fun copyLink() {
        val url = link(CaptureService.status) ?: return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("WaveCast", url))
        toast("Ссылка скопирована")
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_PROJECTION = 2
        private val ACCENT = Color.parseColor("#4ADE80")
        private val ROOM_RE = Regex("^[a-z0-9][a-z0-9_-]{0,47}$")
        private const val ROOM_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
        private const val KEY_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        private fun random(chars: String, n: Int): String {
            val r = SecureRandom()
            return (1..n).map { chars[r.nextInt(chars.length)] }.joinToString("")
        }
    }
}
