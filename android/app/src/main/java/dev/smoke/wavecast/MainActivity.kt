package dev.smoke.wavecast

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : Activity() {

    private val s by lazy { Settings(this) }

    private lateinit var btn: PulseButton
    private lateinit var tvCaption: TextView
    private lateinit var shareCard: LinearLayout
    private lateinit var tvRoom: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLink: TextView
    private lateinit var ivQr: ImageView
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
        ivQr.setOnClickListener { bigQr() }
        updBtn.setOnClickListener { update() }
    }

    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val periodicCheck = object : Runnable {
        override fun run() {
            Updater.check(this@MainActivity) { rel, _ -> renderUpdate(rel) }
            ticker.postDelayed(this, Updater.CHECK_EVERY_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        CaptureService.listener = { render(it) }
        render(CaptureService.status)
        periodicCheck.run()                        // при каждом возврате и потом раз в 5 минут, пока экран открыт
    }

    override fun onPause() {
        CaptureService.listener = null
        ticker.removeCallbacks(periodicCheck)
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
        btn.live = on
        tvCaption.visibility = if (on) View.GONE else View.VISIBLE
        tvLive.text = if (on) "в эфире" else "не в эфире"
        tvLive.setTextColor(if (on) Ui.MILK else Ui.DIM)
        tvLive.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(if (on) Ui.CORAL else Ui.DIM), null, null, null)

        tvRoom.text = android.text.SpannableStringBuilder("Эфир  ").append(s.room,
            android.text.style.ForegroundColorSpan(Ui.MILK), 0).append("  ›")
        tvRoom.visibility = if (on) View.GONE else View.VISIBLE
        tvHint.visibility = tvRoom.visibility

        val total = st.p2pListeners + st.localListeners
        tvCount.visibility = if (on) View.VISIBLE else View.GONE
        tvCount.text = "$total"
        tvCountLabel.visibility = tvCount.visibility
        tvCountLabel.text = (if (total % 10 == 1 && total % 100 != 11) "слушает" else "слушают") + " · " + s.room

        val url = if (on) link(st) else null
        shareCard.visibility = if (url != null) View.VISIBLE else View.GONE
        if (url != null) {
            tvLink.text = url.removePrefix("https://").removePrefix("http://")   // полная ссылка — в QR и при копировании
            if (qrFor != url) { ivQr.setImageBitmap(qr(url)); qrFor = url }
        }

        val sb = StringBuilder()
        if (st.error != null) sb.append("Не получилось: ${st.error}\n\n")
        if (on) {
            if (st.p2pUrl != null && !st.p2pConnected) sb.append("Нет связи с сервисом: ${st.p2pError ?: "подключаюсь…"}\n\n")
            if (st.localUrls.isNotEmpty()) {
                sb.append(if (st.p2pUrl != null) "Рядом и без интернета, в твоей Wi-Fi сети: " else "Ссылка работает только в твоей Wi-Fi сети или на твоей точке доступа")
                if (st.p2pUrl != null) sb.append(st.localUrls.joinToString(", "))
            } else if (st.p2pUrl == null) sb.append("Нет Wi-Fi сети: подключись к Wi-Fi или включи точку доступа")
        }
        tvStatus.text = sb.toString().trim()
        tvStatus.visibility = if (sb.isEmpty()) View.GONE else View.VISIBLE
        renderRequests(if (on) st.requests else emptyList())
        renderUpdate(Updater.available)
    }

    private fun dot(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color); setSize(px(7), px(7))
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(12), px(22), px(24))
        }

        // шапка: марка, статус эфира, настройки
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("WaveCast", 15f, Ui.MILK, Ui.display), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tvLive = label("не в эфире", 13f, Ui.DIM, Ui.bold).apply {
            setPadding(px(12), px(8), px(13), px(8)); background = shape(Ui.PANEL, 99)
            compoundDrawablePadding = px(7); gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tvLive)
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            contentDescription = "Настройки"
            background = shape(Ui.PANEL, 99)
            setPadding(px(10), px(10), px(10), px(10))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            pressable(this)
        }, LinearLayout.LayoutParams(px(40), px(40)).apply { marginStart = px(8) })
        root.addView(head, wide(6))

        // плашка «вышло обновление»
        updCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            background = shape(Ui.PANEL, 20); setPadding(px(16), px(12), px(12), px(12))
        }
        val updText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        updTitle = label("", 15f, Ui.MILK, Ui.bold)
        updNotes = label("", 13f, Ui.DIM).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, px(3), 0, 0) }
        updText.addView(updTitle); updText.addView(updNotes)
        updCard.addView(updText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        updBtn = chipButton("Обновить", Ui.LILAC, Ui.INK)
        updCard.addView(updBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(10) })
        root.addView(updCard, wide(16))

        root.addView(label("Твоё радио", 34f, Ui.MILK, Ui.display).apply { letterSpacing = -0.04f }, wide(28))
        tvRoom = label("", 15f, Ui.DIM).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, px(8), 0, px(8))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        root.addView(tvRoom, wide(2))

        // кто просится в эфир — наверху, это главное во время эфира
        reqBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(reqBox, wide())

        // сцена: круглая кнопка по центру свободного места
        val stage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        btn = PulseButton(this)
        stage.addView(btn, LinearLayout.LayoutParams(px(290), px(290)))
        tvCaption = label("В эфир", 18f, Ui.MILK, Ui.display).apply { gravity = Gravity.CENTER; letterSpacing = -0.02f }
        stage.addView(tvCaption, wide(-14))
        tvCount = label("0", 44f, Ui.LILAC, Ui.display).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        stage.addView(tvCount, wide(-10))
        tvCountLabel = label("", 14f, Ui.DIM).apply {
            gravity = Gravity.CENTER; visibility = View.GONE; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        stage.addView(tvCountLabel, wide(6))
        root.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(8); bottomMargin = px(8) })

        tvHint = label("Включай музыку в любом приложении — друзья услышат то же, что и ты", 15f, Ui.DIM).apply {
            gravity = Gravity.CENTER; setLineSpacing(px(3).toFloat(), 1f); setPadding(px(12), 0, px(12), 0)
        }
        root.addView(tvHint, wide())

        // карточка «Позови друзей»: сверху QR и ссылка, снизу две кнопки поровну на всю ширину
        shareCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = shape(Ui.PANEL, 22); setPadding(px(14), px(14), px(14), px(14))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        ivQr = ImageView(this).apply {
            background = shape(Ui.MILK, 14); setPadding(px(7), px(7), px(7), px(7))
            contentDescription = "QR-код эфира, нажми, чтобы увеличить"
        }
        top.addView(ivQr, LinearLayout.LayoutParams(px(88), px(88)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14), 0, 0, 0) }
        col.addView(label("Позови друзей", 16f, Ui.MILK, Ui.bold))
        tvLink = label("", 13f, Ui.LILAC, Ui.bold).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, px(6), 0, px(6))
            setOnClickListener { copyLink() }
        }
        col.addView(tvLink, wide())
        col.addView(label("Нажми на QR, чтобы показать его крупно", 12f, Ui.DIM).apply { maxLines = 2 })
        top.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        shareCard.addView(top)
        val row = LinearLayout(this)
        fun half(t: String, start: Int, onClick: () -> Unit) = row.addView(chipButton(t, Ui.BG, Ui.MILK).apply {
            maxLines = 1; setPadding(px(8), px(12), px(8), px(12))
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(start) })
        half("Поделиться", 0) { share() }
        half("Копировать", 8) { copyLink() }
        shareCard.addView(row, wide(12))
        root.addView(shareCard, wide())

        tvStatus = label("", 13f, Ui.DIM).apply { setLineSpacing(px(2).toFloat(), 1f); visibility = View.GONE; gravity = Gravity.CENTER }
        root.addView(tvStatus, wide(12))

        return ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) }
    }

    /** QR на весь экран — чтобы друг рядом мог навести камеру. */
    private fun bigQr() {
        val url = link(CaptureService.status) ?: return
        val d = android.app.Dialog(this)
        d.setContentView(ImageView(this).apply {
            setImageBitmap(qr(url)); background = shape(Ui.MILK, 24); setPadding(px(18), px(18), px(18), px(18))
            setOnClickListener { d.dismiss() }
        }, android.view.ViewGroup.LayoutParams(px(320), px(320)))
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        d.show()
    }

    private fun renderRequests(list: List<CaptureService.Request>) {
        if (list == shownRequests) return
        shownRequests = list
        reqBox.removeAllViews()
        for (r in list) {
            val card = card(22, 16)
            card.addView(label("${r.name} хочет подключиться", 16f, Ui.MILK, Ui.bold))
            card.addView(label(if (r.viaWifi) "Из твоей Wi-Fi сети" else "По ссылке на эфир", 14f, Ui.DIM).apply { setPadding(0, px(4), 0, 0) })
            val row = LinearLayout(this).apply { setPadding(0, px(12), 0, 0) }
            fun choice(t: String, allow: Boolean, fill: Int, ink: Int) = pill(fill, ink, 15f).apply {
                text = t; setPadding(0, px(13), 0, px(13))
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
            reqBox.addView(card, wide(14))
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
