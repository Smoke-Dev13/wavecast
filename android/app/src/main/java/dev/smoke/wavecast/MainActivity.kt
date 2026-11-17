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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.widget.FrameLayout
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
    private lateinit var qrCard: FrameLayout
    private lateinit var tvLive: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvCountLabel: TextView
    private lateinit var lblService: TextView
    private lateinit var lblRoom: TextView
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
        btn.text = if (on) "Закончить эфир" else "В эфир"
        btn.background = sticker(if (on) GUM else LEMON, 99, 6)
        tvLive.text = if (on) "в эфире" else "не в эфире"
        tvLive.background = sticker(if (on) GUM else MILK, 99, 0, 2)
        val total = s.p2pListeners + s.localListeners
        tvCount.visibility = if (on) View.VISIBLE else View.GONE
        tvCount.text = "$total"
        tvCountLabel.visibility = tvCount.visibility
        tvCountLabel.text = if (total % 10 == 1 && total % 100 != 11) "слушает сейчас" else "слушают сейчас"
        listOf(lblService, etService, lblRoom, etRoom).forEach { it.visibility = if (on) View.GONE else View.VISIBLE }
        val url = if (on) link(s) else null
        listOf(tvLink, qrCard, btnShare).forEach { it.visibility = if (url != null) View.VISIBLE else View.GONE }
        if (url != null) {
            tvLink.text = url
            if (qrFor != url) { ivQr.setImageBitmap(qr(url)); qrFor = url }
        }

        val sb = StringBuilder()
        if (s.error != null) sb.append("Не получилось: ${s.error}\n\n")
        if (!on) {
            sb.append("Нажми «В эфир» и включай музыку где угодно. Друзья услышат то же, что и ты.\n\n")
            sb.append(if (etService.text.isBlank()) "Без адреса сервиса эфир ловится только в твоей Wi-Fi сети или на твоей точке доступа."
                      else "Друзья заходят по ссылке или QR-коду из любой сети.")
        } else {
            if (s.p2pUrl != null && !s.p2pConnected) sb.append("Нет связи с сервисом: ${s.p2pError ?: "подключаюсь…"}\n\n")
            if (s.localUrls.isNotEmpty()) {
                sb.append(if (s.p2pUrl != null) "Рядом и без интернета, в твоей Wi-Fi сети:\n" else "Ссылка работает в твоей Wi-Fi сети или на твоей точке доступа.\n")
                if (s.p2pUrl != null) s.localUrls.forEach { sb.append("$it\n") }
            } else if (s.p2pUrl == null) sb.append("Нет Wi-Fi сети: подключись к Wi-Fi или включи точку доступа.\n")
        }
        tvStatus.text = sb.toString()
    }

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** «Наклейка»: заливка с чернильной обводкой и жёсткой тенью со сдвигом. */
    private fun sticker(fill: Int, radius: Int, shadow: Int = 5, stroke: Int = 3): Drawable {
        fun shape(color: Int, line: Boolean) = GradientDrawable().apply {
            setColor(color); cornerRadius = px(radius).toFloat()
            if (line) setStroke(px(stroke), INK)
        }
        if (shadow == 0) return shape(fill, true)
        return LayerDrawable(arrayOf(shape(INK, false), shape(fill, true))).apply {
            setLayerInset(0, px(shadow), px(shadow), 0, 0)
            setLayerInset(1, 0, 0, px(shadow), px(shadow))
        }
    }

    /** Кнопка «проваливается» в тень при нажатии. */
    @SuppressLint("ClickableViewAccessibility")
    private fun pressable(v: View, shadow: Int) = v.setOnTouchListener { _, e ->
        val d = when (e.actionMasked) { MotionEvent.ACTION_DOWN -> px(shadow) * 0.6f; MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 0f; else -> return@setOnTouchListener false }
        v.animate().translationX(d).translationY(d).setDuration(60).start()
        false
    }

    private fun buildUi(): View {
        window.statusBarColor = UV
        window.navigationBarColor = UV
        val display = Typeface.create("sans-serif-black", Typeface.NORMAL)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(20), px(22), px(28))
        }
        fun text(t: String, size: Float, color: Int = MILK, face: Typeface = medium) = TextView(this).apply {
            text = t; textSize = size; setTextColor(color); typeface = face; includeFontPadding = false
        }
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint; setSingleLine(); textSize = 16f; typeface = medium
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(INK); setHintTextColor(Color.parseColor("#8A84A0"))
            background = sticker(MILK, 16, 0)
            setPadding(px(16), px(14), px(16), px(14))
        }
        fun button(fill: Int, size: Float, shadow: Int) = Button(this).apply {
            isAllCaps = false; textSize = size; typeface = display; setTextColor(INK); letterSpacing = -0.02f
            stateListAnimator = null; background = sticker(fill, 99, shadow)
            setPadding(px(20), px(16), px(20 + shadow), px(16 + shadow))
            pressable(this, shadow)
        }
        fun wide(top: Int = 0) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(top) }
        fun wrap(top: Int = 0, center: Boolean = false) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(top); if (center) gravity = Gravity.CENTER_HORIZONTAL }

        // шапка: марка слева, статус эфира справа
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("WaveCast", 15f, LEMON, display), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tvLive = text("не в эфире", 13f, INK, Typeface.DEFAULT_BOLD).apply { setPadding(px(12), px(7), px(12), px(7)) }
        head.addView(tvLive)
        root.addView(head, wide())

        root.addView(text("Твоё радио", 44f, MILK, display).apply {
            letterSpacing = -0.04f
            setShadowLayer(0.01f, px(3).toFloat(), px(3).toFloat(), INK)
        }, wide(top = 26))

        tvCount = text("0", 72f, LEMON, display).apply { setShadowLayer(0.01f, px(4).toFloat(), px(4).toFloat(), INK); visibility = View.GONE }
        root.addView(tvCount, wide(top = 14))
        tvCountLabel = text("слушают сейчас", 16f).apply { visibility = View.GONE }
        root.addView(tvCountLabel, wide())

        lblService = text("Адрес сервиса", 13f)
        root.addView(lblService, wide(top = 22))
        etService = field("wavecast.твой-аккаунт.workers.dev")
        root.addView(etService, wide(top = 6))
        lblRoom = text("Название эфира", 13f)
        root.addView(lblRoom, wide(top = 14))
        etRoom = field("party")
        root.addView(etRoom, wide(top = 6))

        btn = button(LEMON, 22f, 6)
        root.addView(btn, wide(top = 24))

        // QR-наклейка, слегка повёрнутая
        ivQr = ImageView(this)
        qrCard = FrameLayout(this).apply {
            background = sticker(MILK, 22, 6); rotation = -2f; visibility = View.GONE
            setPadding(px(14), px(14), px(20), px(20))
            addView(ivQr, FrameLayout.LayoutParams(px(200), px(200)))
        }
        root.addView(qrCard, wrap(top = 26, center = true))
        tvLink = text("", 15f, LEMON, Typeface.DEFAULT_BOLD).apply { gravity = Gravity.CENTER; visibility = View.GONE; setPadding(0, px(8), 0, px(8)) }
        root.addView(tvLink, wide(top = 14))
        btnShare = button(MINT, 17f, 4).apply { text = "Позвать друзей"; visibility = View.GONE }
        root.addView(btnShare, wide(top = 8))

        tvStatus = text("", 15f).apply { setLineSpacing(px(3).toFloat(), 1f) }
        root.addView(tvStatus, wide(top = 22))

        return ScrollView(this).apply { setBackgroundColor(UV); isFillViewport = true; addView(root) }
    }

    private fun qr(text: String): Bitmap {
        val n = 480
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, n, n)
        val px = IntArray(n * n) { if (m.get(it % n, it / n)) INK else MILK }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.RGB_565)
    }

    private fun share() {
        val url = link(CaptureService.status) ?: return
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Залетай в мой эфир: $url")
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
        private val UV = Color.parseColor("#5B2EFF")
        private val INK = Color.parseColor("#17102E")
        private val LEMON = Color.parseColor("#FFE94A")
        private val GUM = Color.parseColor("#FF5FA2")
        private val MILK = Color.parseColor("#FFF8EC")
        private val MINT = Color.parseColor("#7CF5C4")
        private val ROOM_RE = Regex("^[a-z0-9][a-z0-9_-]{0,47}$")
        private const val ROOM_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
        private const val KEY_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        private fun random(chars: String, n: Int): String {
            val r = SecureRandom()
            return (1..n).map { chars[r.nextInt(chars.length)] }.joinToString("")
        }
    }
}
