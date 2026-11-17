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
import android.widget.Switch
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
    private lateinit var reqBox: LinearLayout
    private lateinit var swAuto: Switch
    private lateinit var swMute: Switch
    private var shownRequests: List<CaptureService.Request>? = null
    private var qrFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        etService.setText(prefs.getString("service", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVICE)
        etRoom.setText(prefs.getString("room", null) ?: random(ROOM_CHARS, 12))
        btn.setOnClickListener { if (CaptureService.status.running) stop() else askPermissions() }
        btnShare.setOnClickListener { share() }
        swMute.isChecked = prefs.getBoolean("muteMessengers", true)
        swMute.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("muteMessengers", on).apply() }
        swAuto.isChecked = prefs.getBoolean("autoAllow", false)
        swAuto.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("autoAllow", on).apply()
            if (CaptureService.status.running && CaptureService.status.autoAllow != on) {
                startService(Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_AUTO).putExtra(CaptureService.EXTRA_AUTO, on))
            }
        }
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
            .putExtra(CaptureService.EXTRA_AUTO, swAuto.isChecked)
            .putExtra(CaptureService.EXTRA_MUTE_MESSENGERS, swMute.isChecked)
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
        btn.background = shape(if (on) MILK else LILAC, 99)
        tvLive.text = if (on) "в эфире" else "не в эфире"
        tvLive.setTextColor(if (on) CORAL else DIM)
        val total = s.p2pListeners + s.localListeners
        tvCount.visibility = if (on) View.VISIBLE else View.GONE
        tvCount.text = "$total"
        tvCountLabel.visibility = tvCount.visibility
        tvCountLabel.text = if (total % 10 == 1 && total % 100 != 11) "слушает сейчас" else "слушают сейчас"
        swMute.isEnabled = !on
        listOf(lblService, etService, lblRoom, etRoom).forEach { it.visibility = if (on) View.GONE else View.VISIBLE }
        val url = if (on) link(s) else null
        listOf(tvLink, qrCard, btnShare).forEach { it.visibility = if (url != null) View.VISIBLE else View.GONE }
        if (url != null) {
            tvLink.text = url.removePrefix("https://").removePrefix("http://")   // полная ссылка — в QR и при копировании
            if (qrFor != url) { ivQr.setImageBitmap(qr(url)); qrFor = url }
        }

        val sb = StringBuilder()
        if (s.error != null) sb.append("Не получилось: ${s.error}\n\n")
        if (!on) {
            sb.append("Нажми «В эфир» и включай музыку где угодно. Друзья услышат то же, что и ты.\n\n")
            if (!swAuto.isChecked) sb.append("Каждый слушатель сначала просится в эфир, а ты пускаешь его кнопкой в уведомлении.\n\n")
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
        renderRequests(if (on) s.requests else emptyList())
    }

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, radius: Int): Drawable = GradientDrawable().apply {
        setColor(fill); cornerRadius = px(radius).toFloat()
    }

    /** Кнопка слегка сжимается при нажатии. */
    @SuppressLint("ClickableViewAccessibility")
    private fun pressable(v: View) = v.setOnTouchListener { _, e ->
        val k = when (e.actionMasked) { MotionEvent.ACTION_DOWN -> 0.96f; MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1f; else -> return@setOnTouchListener false }
        v.animate().scaleX(k).scaleY(k).setDuration(70).start()
        false
    }

    private fun buildUi(): View {
        window.statusBarColor = BG
        window.navigationBarColor = BG
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
            setTextColor(MILK); setHintTextColor(DIM)
            background = shape(PANEL, 16)
            setPadding(px(16), px(14), px(16), px(14))
        }
        fun button(fill: Int, ink: Int, size: Float) = Button(this).apply {
            isAllCaps = false; textSize = size; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink)
            stateListAnimator = null; background = shape(fill, 99)
            setPadding(px(20), px(17), px(20), px(17))
            pressable(this)
        }
        fun wide(top: Int = 0) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(top) }
        fun wrap(top: Int = 0, center: Boolean = false) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(top); if (center) gravity = Gravity.CENTER_HORIZONTAL }

        // шапка: марка слева, статус эфира справа
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("WaveCast", 15f, MILK, display), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tvLive = text("не в эфире", 13f, DIM, Typeface.DEFAULT_BOLD).apply { setPadding(px(12), px(7), px(12), px(7)); background = shape(PANEL, 99) }
        head.addView(tvLive)
        root.addView(head, wide())

        root.addView(text("Твоё радио", 42f, MILK, display).apply { letterSpacing = -0.04f }, wide(top = 26))

        tvCount = text("0", 72f, LILAC, display).apply { visibility = View.GONE }
        root.addView(tvCount, wide(top = 14))
        tvCountLabel = text("слушают сейчас", 16f, DIM).apply { visibility = View.GONE }
        root.addView(tvCountLabel, wide())

        lblService = text("Адрес сервиса", 13f, DIM)
        root.addView(lblService, wide(top = 22))
        etService = field("wavecast.твой-аккаунт.workers.dev")
        root.addView(etService, wide(top = 6))
        lblRoom = text("Название эфира", 13f, DIM)
        root.addView(lblRoom, wide(top = 14))
        etRoom = field("party")
        root.addView(etRoom, wide(top = 6))

        btn = button(LILAC, INK, 20f)
        root.addView(btn, wide(top = 24))

        // кто просится в эфир — сразу под счётчиком, это главное во время эфира
        reqBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(reqBox, wide(top = 6))

        swAuto = Switch(this).apply {
            text = "Пускать всех без вопросов"; textSize = 15f; setTextColor(MILK); typeface = medium
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(LILAC, DIM))
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(LILAC, PANEL))
            setPadding(0, px(8), 0, px(8))
        }
        root.addView(swAuto, wide(top = 14))

        swMute = Switch(this).apply {
            text = "Не транслировать мессенджеры"; textSize = 15f; setTextColor(MILK); typeface = medium
            thumbTintList = swAuto.thumbTintList; trackTintList = swAuto.trackTintList
            setPadding(0, px(8), 0, px(8))
        }
        root.addView(swMute, wide(top = 2))
        root.addView(text("Голосовые и звук Telegram, WhatsApp, Viber и других мессенджеров не попадут в эфир", 13f, DIM), wide())

        // QR на светлой карточке
        ivQr = ImageView(this)
        qrCard = FrameLayout(this).apply {
            background = shape(MILK, 24); visibility = View.GONE
            setPadding(px(16), px(16), px(16), px(16))
            addView(ivQr, FrameLayout.LayoutParams(px(200), px(200)))
        }
        root.addView(qrCard, wrap(top = 26, center = true))
        tvLink = text("", 15f, LILAC, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER; visibility = View.GONE; setPadding(0, px(8), 0, px(8))
            // ссылка всегда в одну строку: шрифт уменьшается до 10sp, дальше — многоточие в середине
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setAutoSizeTextTypeUniformWithConfiguration(10, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        root.addView(tvLink, wide(top = 14))
        btnShare = button(PANEL, MILK, 16f).apply { text = "Позвать друзей"; visibility = View.GONE }
        root.addView(btnShare, wide(top = 8))

        tvStatus = text("", 15f, DIM).apply { setLineSpacing(px(3).toFloat(), 1f) }
        root.addView(tvStatus, wide(top = 22))

        return ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; addView(root) }
    }

    private fun renderRequests(list: List<CaptureService.Request>) {
        if (list == shownRequests) return
        shownRequests = list
        reqBox.removeAllViews()
        for (r in list) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(PANEL, 20)
                setPadding(px(16), px(14), px(16), px(14))
            }
            card.addView(TextView(this).apply {
                text = "${r.name} хочет подключиться"; textSize = 17f; setTextColor(MILK); typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply {
                text = if (r.viaWifi) "Из твоей Wi-Fi сети" else "По ссылке на эфир"; textSize = 14f; setTextColor(DIM)
            })
            val row = LinearLayout(this).apply { setPadding(0, px(12), 0, 0) }
            fun choice(label: String, allow: Boolean, fill: Int, ink: Int) = Button(this).apply {
                text = label; isAllCaps = false; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink)
                stateListAnimator = null; background = shape(fill, 99); setPadding(0, px(12), 0, px(12))
                pressable(this)
                setOnClickListener {
                    startService(Intent(this@MainActivity, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_DECIDE)
                        .putExtra(CaptureService.EXTRA_REQ, r.key).putExtra(CaptureService.EXTRA_ALLOW, allow))
                }
            }
            row.addView(choice("Пустить", true, LILAC, INK), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(View(this), LinearLayout.LayoutParams(px(10), 1))
            row.addView(choice("Отклонить", false, BG, MILK), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)
            reqBox.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
        }
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
        private val BG = Color.parseColor("#191627")
        private val INK = Color.parseColor("#0F0D1A")
        private val LILAC = Color.parseColor("#BFB0F5")
        private val CORAL = Color.parseColor("#F0A08C")
        private val MILK = Color.parseColor("#EFEAE2")
        private val PANEL = Color.parseColor("#2A2640")
        private val DIM = Color.parseColor("#9C96B0")
        /** Сигнальный сервис по умолчанию — чтобы эфир по ссылке работал без настройки. */
        const val DEFAULT_SERVICE = "wavecast.smokedev13.workers.dev"
        private val ROOM_RE = Regex("^[a-z0-9][a-z0-9_-]{0,47}$")
        private const val ROOM_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
        private const val KEY_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        private fun random(chars: String, n: Int): String {
            val r = SecureRandom()
            return (1..n).map { chars[r.nextInt(chars.length)] }.joinToString("")
        }
    }
}
