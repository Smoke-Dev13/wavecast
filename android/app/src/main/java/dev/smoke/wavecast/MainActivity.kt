package dev.smoke.wavecast

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : Activity() {

    private val s by lazy { Settings(this) }

    private lateinit var btn: Button
    private lateinit var btnShare: Button
    private lateinit var tvRoom: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLink: TextView
    private lateinit var ivQr: ImageView
    private lateinit var qrCard: FrameLayout
    private lateinit var tvLive: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvCountLabel: TextView
    private lateinit var reqBox: LinearLayout
    private lateinit var updCard: LinearLayout
    private lateinit var updTitle: TextView
    private lateinit var updNotes: TextView
    private lateinit var updBtn: Button
    private var updBusy = false
    private var shownRequests: List<CaptureService.Request>? = null
    private var qrFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paintBars()
        setContentView(buildUi())
        btn.setOnClickListener { if (CaptureService.status.running) stop() else askPermissions() }
        btnShare.setOnClickListener { share() }
        tvLink.setOnClickListener { copyLink() }
        updBtn.setOnClickListener { update() }
    }

    override fun onResume() {
        super.onResume()
        CaptureService.listener = { render(it) }
        render(CaptureService.status)
        Updater.check(this) { rel, _ -> renderUpdate(rel) }
    }

    override fun onPause() {
        CaptureService.listener = null
        super.onPause()
    }

    // ---- запуск эфира ----

    private fun askPermissions() {
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
            .putExtra(CaptureService.EXTRA_SERVICE, s.service)
            .putExtra(CaptureService.EXTRA_ROOM, s.room)
            .putExtra(CaptureService.EXTRA_HOST_KEY, s.hostKey)
            .putExtra(CaptureService.EXTRA_AUTO, s.autoAllow)
            .putExtra(CaptureService.EXTRA_MUTE_MESSENGERS, s.muteMessengers)
        startForegroundService(i)
    }

    private fun stop() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
    }

    // ---- обновление ----

    private fun renderUpdate(rel: Updater.Release?) {
        val show = rel != null && !CaptureService.status.running
        updCard.visibility = if (show) View.VISIBLE else View.GONE
        if (rel == null || updBusy) return
        updTitle.text = "Вышла версия ${rel.versionName}"
        updNotes.text = rel.notes
        updNotes.visibility = if (rel.notes.isBlank()) View.GONE else View.VISIBLE
        updBtn.text = "Обновить"
    }

    private fun update() {
        val rel = Updater.available ?: return
        if (!Updater.ensureInstallAllowed(this)) { toast("Разреши WaveCast устанавливать обновления и нажми «Обновить» ещё раз"); return }
        updBusy = true
        updBtn.isEnabled = false
        Updater.downloadAndInstall(this, rel) { text, finished ->
            updBtn.text = text ?: "Обновить"
            if (finished) { updBusy = false; updBtn.isEnabled = true }
        }
    }

    // ---- экран ----

    private fun link(st: CaptureService.Status) = st.p2pUrl ?: st.localUrls.firstOrNull()

    private fun render(st: CaptureService.Status) {
        val on = st.running
        btn.text = if (on) "Закончить эфир" else "В эфир"
        btn.background = shape(if (on) Ui.MILK else Ui.LILAC, 99)
        tvLive.text = if (on) "в эфире" else "не в эфире"
        tvLive.setTextColor(if (on) Ui.CORAL else Ui.DIM)

        tvRoom.text = "Эфир «${s.room}»"
        tvRoom.visibility = if (on) View.GONE else View.VISIBLE
        tvHint.visibility = tvRoom.visibility

        val total = st.p2pListeners + st.localListeners
        tvCount.visibility = if (on) View.VISIBLE else View.GONE
        tvCount.text = "$total"
        tvCountLabel.visibility = tvCount.visibility
        tvCountLabel.text = if (total % 10 == 1 && total % 100 != 11) "слушает сейчас" else "слушают сейчас"

        val url = if (on) link(st) else null
        listOf(tvLink, qrCard, btnShare).forEach { it.visibility = if (url != null) View.VISIBLE else View.GONE }
        if (url != null) {
            tvLink.text = url.removePrefix("https://").removePrefix("http://")   // полная ссылка — в QR и при копировании
            if (qrFor != url) { ivQr.setImageBitmap(qr(url)); qrFor = url }
        }

        val sb = StringBuilder()
        if (st.error != null) sb.append("Не получилось: ${st.error}\n\n")
        if (on) {
            if (st.p2pUrl != null && !st.p2pConnected) sb.append("Нет связи с сервисом: ${st.p2pError ?: "подключаюсь…"}\n\n")
            if (st.localUrls.isNotEmpty()) {
                sb.append(if (st.p2pUrl != null) "Рядом и без интернета, в твоей Wi-Fi сети:\n" else "Ссылка работает в твоей Wi-Fi сети или на твоей точке доступа.\n")
                if (st.p2pUrl != null) st.localUrls.forEach { sb.append("$it\n") }
            } else if (st.p2pUrl == null) sb.append("Нет Wi-Fi сети: подключись к Wi-Fi или включи точку доступа.\n")
        }
        tvStatus.text = sb.toString().trim()
        tvStatus.visibility = if (sb.isEmpty()) View.GONE else View.VISIBLE
        renderRequests(if (on) st.requests else emptyList())
        renderUpdate(Updater.available)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(14), px(22), px(28))
        }

        // шапка: марка, статус эфира, настройки
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("WaveCast", 15f, Ui.MILK, Ui.display), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tvLive = label("не в эфире", 13f, Ui.DIM, Typeface.DEFAULT_BOLD).apply {
            setPadding(px(12), px(7), px(12), px(7)); background = shape(Ui.PANEL, 99)
        }
        head.addView(tvLive)
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            contentDescription = "Настройки"
            setPadding(px(10), px(10), px(4), px(10))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(px(48), px(48)).apply { marginStart = px(6) })
        root.addView(head, wide())

        // плашка «вышло обновление»
        updCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = shape(Ui.PANEL, 20); setPadding(px(16), px(14), px(16), px(14))
        }
        updTitle = label("", 17f, Ui.MILK, Typeface.DEFAULT_BOLD)
        updNotes = label("", 14f, Ui.DIM).apply { maxLines = 3; setPadding(0, px(6), 0, 0) }
        updBtn = pill(Ui.LILAC, Ui.INK, 15f).apply { setPadding(0, px(12), 0, px(12)) }
        updCard.addView(updTitle); updCard.addView(updNotes); updCard.addView(updBtn, wide(12))
        root.addView(updCard, wide(16))

        root.addView(label("Твоё радио", 42f, Ui.MILK, Ui.display).apply { letterSpacing = -0.04f }, wide(28))
        tvRoom = label("", 16f, Ui.DIM).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        root.addView(tvRoom, wide(10))

        tvCount = label("0", 72f, Ui.LILAC, Ui.display).apply { visibility = View.GONE }
        root.addView(tvCount, wide(14))
        tvCountLabel = label("слушают сейчас", 16f, Ui.DIM).apply { visibility = View.GONE }
        root.addView(tvCountLabel, wide())

        btn = pill(Ui.LILAC, Ui.INK, 20f)
        root.addView(btn, wide(28))
        tvHint = label("Включай музыку в любом приложении — друзья услышат то же, что и ты", 15f, Ui.DIM).apply {
            setLineSpacing(px(3).toFloat(), 1f)
        }
        root.addView(tvHint, wide(16))

        // кто просится в эфир — сразу под кнопкой, это главное во время эфира
        reqBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(reqBox, wide(6))

        // QR на светлой карточке
        ivQr = ImageView(this)
        qrCard = FrameLayout(this).apply {
            background = shape(Ui.MILK, 24); visibility = View.GONE
            setPadding(px(16), px(16), px(16), px(16))
            addView(ivQr, FrameLayout.LayoutParams(px(200), px(200)))
        }
        root.addView(qrCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(26); gravity = Gravity.CENTER_HORIZONTAL })
        tvLink = label("", 15f, Ui.LILAC, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER; visibility = View.GONE; setPadding(0, px(8), 0, px(8))
            // ссылка всегда в одну строку: шрифт уменьшается до 10sp, дальше — многоточие в середине
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setAutoSizeTextTypeUniformWithConfiguration(10, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        root.addView(tvLink, wide(14))
        btnShare = pill(Ui.PANEL, Ui.MILK, 16f).apply { text = "Позвать друзей"; visibility = View.GONE }
        root.addView(btnShare, wide(8))

        tvStatus = label("", 15f, Ui.DIM).apply { setLineSpacing(px(3).toFloat(), 1f); visibility = View.GONE }
        root.addView(tvStatus, wide(22))

        return ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) }
    }

    private fun renderRequests(list: List<CaptureService.Request>) {
        if (list == shownRequests) return
        shownRequests = list
        reqBox.removeAllViews()
        for (r in list) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(Ui.PANEL, 20)
                setPadding(px(16), px(14), px(16), px(14))
            }
            card.addView(label("${r.name} хочет подключиться", 17f, Ui.MILK, Typeface.DEFAULT_BOLD))
            card.addView(label(if (r.viaWifi) "Из твоей Wi-Fi сети" else "По ссылке на эфир", 14f, Ui.DIM).apply { setPadding(0, px(4), 0, 0) })
            val row = LinearLayout(this).apply { setPadding(0, px(12), 0, 0) }
            fun choice(t: String, allow: Boolean, fill: Int, ink: Int) = pill(fill, ink, 15f).apply {
                text = t; setPadding(0, px(12), 0, px(12))
                setOnClickListener {
                    startService(Intent(this@MainActivity, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_DECIDE)
                        .putExtra(CaptureService.EXTRA_REQ, r.key).putExtra(CaptureService.EXTRA_ALLOW, allow))
                }
            }
            row.addView(choice("Пустить", true, Ui.LILAC, Ui.INK), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(View(this), LinearLayout.LayoutParams(px(10), 1))
            row.addView(choice("Отклонить", false, Ui.BG, Ui.MILK), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)
            reqBox.addView(card, wide(10))
        }
    }

    private fun qr(text: String): Bitmap {
        val n = 480
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, n, n)
        val px = IntArray(n * n) { if (m.get(it % n, it / n)) Ui.INK else Ui.MILK }
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

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_PROJECTION = 2
    }
}
