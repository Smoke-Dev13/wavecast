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
    private lateinit var tiles: LinearLayout
    private lateinit var tileListen: TextView
    private lateinit var tileWait: TextView
    private lateinit var tileReact: TextView
    private lateinit var tvArtist: TextView
    private lateinit var btnBox: android.widget.FrameLayout
    private lateinit var scroll: ScrollView
    private val backdrop by lazy { Backdrop(this) }
    private var tintFor: Any? = null
    private val DEFAULT_PAL = Any()
    private var tintAnim: android.animation.ValueAnimator? = null
    private lateinit var brand: TextView
    private lateinit var reqBox: LinearLayout
    private lateinit var peopleCard: LinearLayout
    private lateinit var btnChat: Button
    private lateinit var peopleBox: LinearLayout
    private lateinit var peopleTitle: TextView
    private lateinit var tvNow: TextView
    private lateinit var nowRow: LinearLayout
    private lateinit var ivNow: ImageView
    private lateinit var floats: android.widget.FrameLayout
    private lateinit var npCard: LinearLayout
    private lateinit var clipCard: LinearLayout
    private lateinit var npTitle: TextView
    private lateinit var npText: TextView
    private lateinit var npPrimary: Button
    private lateinit var npRetry: Button
    private lateinit var npSkip: TextView
    private var shownPeople: List<CaptureService.Person>? = null
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
        CaptureService.reactionListener = { name, e -> floatReaction(name, e) }
        render(CaptureService.status)
        periodicCheck.run()                        // при каждом возврате и потом раз в 5 минут, пока экран открыт
        lastTotal = 0L; motion.start()
    }

    // живой неон: пока экран открыт, пятна фона плывут, волна и кольцо переливаются по палитре обложки
    private var animTime = 0f
    private var clipTint = false
    private var lastTotal = 0L
    private val motion = android.animation.TimeAnimator().apply {
        setTimeListener { _, total, _ ->
            if (android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) return@setTimeListener
            backdrop.beat = (backdrop.beat - 0.07f).coerceAtLeast(0f)
            animTime += (total - lastTotal) / 1000f / backdrop.look.mv      // темп — по жанру
            lastTotal = total
            backdrop.time = animTime
            // клип: подсветка повторяет цвета кадра (плавно догоняет их)
            val cp = CaptureService.clipPalette
            if (cp != null) {
                tintAnim?.cancel(); clipTint = true
                val ev = android.animation.ArgbEvaluator()
                val pal = IntArray(4) { i -> ev.evaluate(0.1f, backdrop.palette[i], cp[i]) as Int }
                backdrop.palette = pal; backdrop.neonA = pal[0]; backdrop.glowColor = pal[1]
                Ui.bracket = (pal[1] and 0xFFFFFF) or 0xE6000000.toInt()
            } else if (clipTint) { clipTint = false; tintFor = null; render(CaptureService.status) }   // клип кончился — цвета жанра
            btn.neonA = backdrop.colorAt(0f); btn.neonB = backdrop.colorAt(1.5f)
        }
    }

    override fun onPause() {
        CaptureService.listener = null
        CaptureService.reactionListener = null
        ticker.removeCallbacks(periodicCheck)
        motion.cancel()
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
            .putExtra(CaptureService.EXTRA_NOW_PLAYING, s.showNowPlaying)
            .putExtra(CaptureService.EXTRA_CLIPS, s.showClips)
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
        tvLive.text = if (on) "В ЭФИРЕ" else "НЕ В ЭФИРЕ"
        tvLive.setTextColor(if (on) Ui.VOLT else Ui.DIM)
        tvLive.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(if (on) Ui.VOLT else Ui.DIM), null, null, null)
        tvLive.background = if (on) GradientDrawable().apply { setColor(0x33F7F04A); cornerRadius = px(99).toFloat(); setStroke(px(1), Ui.VOLT) } else glass(99)
        tvLive.setShadowLayer(if (on) px(8).toFloat() else 0f, 0f, 0f, Ui.VOLT)

        if (tvRoom.text.toString() != s.room) { tvRoom.text = s.room; fitOneLine(tvRoom) }
        tvRoom.isClickable = !on
        tvHint.visibility = if (on) View.GONE else View.VISIBLE

        nowRow.visibility = if (on && st.nowTitle != null) View.VISIBLE else View.GONE
        tvNow.text = st.nowTitle ?: ""
        // метка жанра рядом с исполнителем
        val genre = if (on) st.nowGenre else null
        tvArtist.text = if (genre == null) st.nowArtist ?: "" else android.text.SpannableStringBuilder(st.nowArtist ?: "")
            .apply { if (isNotEmpty()) append("   ") }
            .append(genre, android.text.style.ForegroundColorSpan(Ui.VOLT), 0)
        val look = Themes.of(if (on) st.nowTheme else null)
        if (look != backdrop.look) { backdrop.look = look; btn.look = look }
        tvArtist.visibility = if (st.nowArtist.isNullOrBlank() && genre == null) View.GONE else View.VISIBLE
        ivNow.visibility = if (st.nowCover != null) View.VISIBLE else View.GONE
        if (st.nowCover != null) ivNow.setImageBitmap(st.nowCover)
        // свечение перетекает в цвет обложки (без эфира — обычный сиреневый)
        val art = if (on) st.nowCover else null
        val themePal = Themes.of(if (on) st.nowTheme else null).palette
        val tintKey: Any? = themePal ?: DEFAULT_PAL
        if (tintKey !== tintFor) {
            tintFor = tintKey
            // палитра обложки перетекает: пятна фона, сияния, волна, кнопка, уголки, подсветка текста
            val to = themePal ?: Backdrop.paletteFrom(null)         // цвета под музыку: по жанру; жанр не найден — фирменный неон, не кадр клипа
            val from = backdrop.palette.copyOf()
            val ev = android.animation.ArgbEvaluator()
            tintAnim?.cancel()
            tintAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2200
                addUpdateListener {
                    val f = it.animatedFraction
                    val pal = IntArray(4) { i -> ev.evaluate(f, from[i], to[i]) as Int }
                    backdrop.palette = pal
                    backdrop.neonA = pal[0]; backdrop.glowColor = pal[1]
                    tvNow.setShadowLayer(px(10).toFloat(), 0f, 0f, pal[1])
                    tvRoom.setShadowLayer(px(9).toFloat(), px(1).toFloat(), 0f, pal[0])
                    brand.setShadowLayer(px(6).toFloat(), px(1).toFloat(), 0f, pal[0])
                    Ui.bracket = (pal[1] and 0xFFFFFF) or 0xE6000000.toInt()
                    tiles.invalidate(); for (i in 0 until tiles.childCount) tiles.getChildAt(i).invalidate(); ivNow.invalidate()
                }
                start()
            }
        }
        npCard.visibility = if (!on && s.showNowPlaying && !NowPlaying.allowed(this)) View.VISIBLE else View.GONE
        clipCard.visibility = if (!on && s.showClips && !s.clipsAsked && !ClipCapture.allowed(this) && npCard.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        tvLive.text = if (on && st.clipLive) "В ЭФИРЕ · КЛИП" else tvLive.text
        if (s.nowPlayingAsked) {
            // уже отправляли в настройки, а доступа нет — скорее всего, «Настройки с ограниченным доступом»
            npTitle.text = "Android не дал доступ?"
            npText.text = "Если было окно «Настройки с ограниченным доступом»: открой настройки WaveCast, нажми ⋮ в правом верхнем углу → «Разрешить ограниченные настройки». Потом нажми «Повторить»"
            npPrimary.text = "Настройки WaveCast"
            npRetry.text = "Повторить"
            npSkip.visibility = View.VISIBLE
        } else {
            npTitle.text = "Покажи друзьям, что играет"
            npText.text = "Название трека и обложка из твоего плеера появятся у слушателей. Android попросит доступ к уведомлениям — сами уведомления WaveCast не читает"
            npPrimary.text = "Разрешить"
            npRetry.text = "Не надо"
            npSkip.visibility = View.GONE
        }
        renderPeople(if (on) st.p2pPeople + st.wifiPeople else emptyList())
        btnChat.visibility = if (on) View.VISIBLE else View.GONE
        btnChat.text = if (st.chatUnread > 0) "Чат эфира +${st.chatUnread}" else "Чат эфира"
        btnChat.background = if (st.chatUnread > 0) shape(Ui.GLOW, 99) else glass(99)
        btnChat.setTextColor(if (st.chatUnread > 0) Ui.INK else Ui.MILK)

        val total = st.p2pListeners + st.localListeners
        tiles.visibility = if (on) View.VISIBLE else View.GONE
        tileListen.text = "$total"
        (tileListen.tag as TextView).text = if (total % 10 == 1 && total % 100 != 11) "слушает" else "слушают"
        tileWait.text = "${st.requests.size}"
        tileWait.setTextColor(if (st.requests.isNotEmpty()) Ui.CORAL else Ui.MILK)
        tileReact.text = "${CaptureService.reactionCount}"

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
        brand = label("WAVECAST", 18f, Ui.MILK, Ui.tech).apply { letterSpacing = 0.08f; setShadowLayer(px(6).toFloat(), px(1).toFloat(), 0f, Ui.NEON) }
        head.addView(brand,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tvLive = label("НЕ В ЭФИРЕ", 12f, Ui.DIM, Ui.tech).apply {
            letterSpacing = 0.06f
            setPadding(px(12), px(9), px(13), px(9)); background = glass(99)
            compoundDrawablePadding = px(7); gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tvLive)
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            contentDescription = "Настройки"
            background = glass(99)
            setPadding(px(10), px(10), px(10), px(10))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            pressable(this)
        }, LinearLayout.LayoutParams(px(40), px(40)).apply { marginStart = px(8) })
        root.addView(head, wide(6))

        // плашка «вышло обновление»
        updCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            background = glass(20); setPadding(px(16), px(12), px(12), px(12))
        }
        val updText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        updTitle = label("", 15f, Ui.MILK, Ui.bold)
        updNotes = label("", 13f, Ui.DIM).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, px(3), 0, 0) }
        updText.addView(updTitle); updText.addView(updNotes)
        updCard.addView(updText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        updBtn = chipButton("Обновить", Ui.LILAC, Ui.INK)
        updCard.addView(updBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(10) })
        root.addView(updCard, wide(16))

        root.addView(label("Частота", 13f, Ui.DIM), wide(26))
        // название эфира всегда одной строкой: шрифт уменьшается, пока не влезет (слово не рвётся)
        tvRoom = label("", 28f, Ui.MILK, Ui.tech).apply {
            setShadowLayer(px(9).toFloat(), px(1).toFloat(), 0f, Ui.NEON)
            maxLines = 1; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, px(4), 0, px(4))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) fitOneLine(v as TextView) }
        }
        root.addView(tvRoom, wide(2))

        // предложение показать друзьям, что играет (и подсказка, если Android заблокировал доступ)
        npCard = card(22, 16).apply { visibility = View.GONE }
        npTitle = label("", 16f, Ui.MILK, Ui.bold)
        npCard.addView(npTitle)
        npText = label("", 13f, Ui.DIM).apply { setLineSpacing(px(2).toFloat(), 1f) }
        npCard.addView(npText, wide(6))
        val npRow = LinearLayout(this)
        npPrimary = chipButton("", Ui.LILAC, Ui.INK).apply {
            setOnClickListener {
                if (s.nowPlayingAsked) openAppDetails()
                else { s.nowPlayingAsked = true; s.showNowPlaying = true; SettingsActivity.openListenerSettings(this@MainActivity) }
            }
        }
        npRow.addView(npPrimary)
        // вторая кнопка: «Не надо» при первом предложении, «Повторить» — если Android не дал доступ
        npRetry = chipButton("", Ui.BG, Ui.MILK).apply {
            setOnClickListener {
                if (s.nowPlayingAsked) SettingsActivity.openListenerSettings(this@MainActivity)
                else { s.nowPlayingAsked = true; s.showNowPlaying = false; npCard.visibility = View.GONE }
            }
        }
        npRow.addView(npRetry, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8) })
        npCard.addView(npRow, wide(12))
        npSkip = label("Не показывать", 13f, Ui.DIM).apply {
            setPadding(0, px(10), px(10), px(2))
            setOnClickListener { s.showNowPlaying = false; npCard.visibility = View.GONE }
        }
        npCard.addView(npSkip)
        root.addView(npCard, wide(14))

        // предложение показывать друзьям клипы (нужен «Доступ к истории использования»)
        clipCard = card(22, 16).apply { visibility = View.GONE }
        clipCard.addView(label("Покажи друзьям клип", 16f, Ui.MILK, Ui.bold))
        clipCard.addView(label("Когда смотришь YouTube или другой видеоплеер, друзья увидят клип, а подсветка повторит цвета кадра. Разреши WaveCast «Доступ к истории использования» — так он понимает, что открыт плеер", 13f, Ui.DIM).apply { setLineSpacing(px(2).toFloat(), 1f) }, wide(6))
        val clipRow = LinearLayout(this)
        clipRow.addView(chipButton("Разрешить", Ui.LILAC, Ui.INK).apply {
            setOnClickListener { s.clipsAsked = true; s.showClips = true; SettingsActivity.openUsageSettings(this@MainActivity) } })
        clipRow.addView(chipButton("Не надо", Ui.BG, Ui.MILK).apply {
            setOnClickListener { s.clipsAsked = true; s.showClips = false; clipCard.visibility = View.GONE } },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8) })
        clipCard.addView(clipRow, wide(12))
        root.addView(clipCard, wide(14))

        // кто просится в эфир — наверху, это главное во время эфира
        reqBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(reqBox, wide())

        // сцена: круглая кнопка по центру свободного места
        // сцена: волна поперёк экрана, кнопка эфира на ней, сверху — слой для летящих реакций
        val stage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; clipChildren = false }
        btn = PulseButton(this).apply { onLevel = { backdrop.lv = it }; onBeat = { backdrop.beat = 1f; if (backdrop.look.shake) shake() } }
        btnBox = android.widget.FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val full = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        btnBox.addView(btn, android.widget.FrameLayout.LayoutParams(full, full))
        floats = android.widget.FrameLayout(this).apply { clipChildren = false; isClickable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        btnBox.addView(floats, android.widget.FrameLayout.LayoutParams(full, full))
        // волна идёт от края до края экрана, поверх отступов
        stage.addView(btnBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(250)).apply {
            marginStart = -px(22); marginEnd = -px(22) })
        tvCaption = label("В ЭФИР", 15f, Ui.MILK, Ui.tech).apply { gravity = Gravity.CENTER; letterSpacing = 0.1f }
        stage.addView(tvCaption, wide(-6))
        root.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(4); bottomMargin = px(4) })

        // что играет: обложка со скобами (нажми — откроется крупно), крупное тонкое название
        nowRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        ivNow = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = hud(2); setPadding(px(1), px(1), px(1), px(1))
            contentDescription = "Обложка, нажми, чтобы увеличить"
            setOnClickListener { bigCover() }
        }
        nowRow.addView(ivNow, LinearLayout.LayoutParams(px(58), px(58)).apply { marginEnd = px(14) })
        val nowText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvNow = label("", 24f, Ui.MILK, Ui.light).apply {
            setShadowLayer(px(10).toFloat(), 0f, 0f, Ui.GLOW)                  // название светится, как неоновая вывеска
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; letterSpacing = -0.03f; setLineSpacing(0f, 1.05f)
        }
        nowText.addView(tvNow)
        tvArtist = label("", 15f, Ui.DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        nowText.addView(tvArtist, wide(5))
        nowRow.addView(nowText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nowRow.setOnClickListener { bigCover() }
        root.addView(nowRow, wide(4))

        // плитки со скобами: слушают / ждут входа / реакций
        tiles = LinearLayout(this).apply { visibility = View.GONE }
        fun tile(sub: String, start: Int): TextView {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = hud(4); setPadding(px(12), px(10), px(12), px(10)) }
            val num = label("0", 24f, Ui.MILK, Ui.tech)
            val cap = label(sub, 12f, Ui.DIM).apply { maxLines = 1 }
            num.tag = cap
            box.addView(num); box.addView(cap, wide(3))
            tiles.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(start) })
            return num
        }
        tileListen = tile("слушают", 0)
        tileWait = tile("ждут входа", 8)
        tileReact = tile("реакций", 8)
        root.addView(tiles, wide(16))

        tvHint = label("Включай музыку в любом приложении — друзья услышат то же, что и ты", 15f, Ui.DIM).apply {
            gravity = Gravity.CENTER; setLineSpacing(px(3).toFloat(), 1f); setPadding(px(12), 0, px(12), 0)
        }
        root.addView(tvHint, wide())

        // чат эфира: кнопка со счётчиком новых сообщений
        btnChat = pill(Ui.PANEL, Ui.MILK, 16f).apply {
            visibility = View.GONE; setPadding(0, px(15), 0, px(15))
            setOnClickListener { startActivity(Intent(this@MainActivity, ChatActivity::class.java)) }
        }
        root.addView(btnChat, wide(14))

        // кто слушает — с кнопкой «Отключить»
        peopleCard = card(22, 0).apply { visibility = View.GONE }
        peopleTitle = label("Слушают", 13f, Ui.DIM).apply { setPadding(px(16), px(14), px(16), px(4)) }
        peopleCard.addView(peopleTitle)
        peopleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        peopleCard.addView(peopleBox)
        root.addView(peopleCard, wide(12))

        // карточка «Позови друзей»: сверху QR и ссылка, снизу две кнопки поровну на всю ширину
        shareCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = glass(22); setPadding(px(14), px(14), px(14), px(14))
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
        root.addView(shareCard, wide(12))

        tvStatus = label("", 13f, Ui.DIM).apply { setLineSpacing(px(2).toFloat(), 1f); visibility = View.GONE; gravity = Gravity.CENTER }
        root.addView(tvStatus, wide(12))

        scroll = ScrollView(this).apply { background = backdrop; isFillViewport = true; addView(root) }
        // свечение фона держим за кнопкой эфира, даже когда экран прокручен
        val follow = {
            backdrop.glowY = (btnBox.top + (btnBox.parent as View).top + btnBox.height / 2f - scroll.scrollY).coerceAtLeast(0f)
        }
        scroll.viewTreeObserver.addOnGlobalLayoutListener { follow() }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> follow() }
        return scroll
    }

    /** Обложка крупно, с названием — как QR. */
    private fun bigCover() {
        val st = CaptureService.status
        val art = st.nowCover ?: return
        val d = android.app.Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { d.dismiss() }
        }
        box.addView(ImageView(this).apply {
            setImageBitmap(art); scaleType = ImageView.ScaleType.CENTER_CROP; background = hud(2); setPadding(px(1), px(1), px(1), px(1))
        }, LinearLayout.LayoutParams(px(320), px(320)))
        box.addView(label(st.nowTitle ?: "", 24f, Ui.MILK, Ui.light).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; letterSpacing = -0.02f
        }, LinearLayout.LayoutParams(px(320), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16) })
        st.nowArtist?.takeIf { it.isNotBlank() }?.let {
            box.addView(label(it, 15f, Ui.DIM).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(px(320), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) })
        }
        d.setContentView(box)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#F2110E1C")))
        d.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        box.gravity = Gravity.CENTER
        d.show()
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

    private fun renderPeople(list: List<CaptureService.Person>) {
        peopleCard.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        if (list == shownPeople) return
        shownPeople = list
        peopleBox.removeAllViews()
        for (p in list) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), px(8), px(10), px(8)) }
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(label(p.name, 16f, Ui.MILK).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            txt.addView(label(if (p.viaWifi) "в Wi-Fi сети" else "по ссылке", 12f, Ui.DIM), wide(2))
            row.addView(txt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(chipButton("Отключить", Ui.BG, Ui.MILK).apply {
                setOnClickListener {
                    startService(Intent(this@MainActivity, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_KICK).putExtra(CaptureService.EXTRA_REQ, p.key))
                }
            })
            peopleBox.addView(row)
        }
        peopleBox.addView(View(this), LinearLayout.LayoutParams(1, px(8)))
    }

    private fun openAppDetails() = startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.parse("package:$packageName")))

    /** Реакция слушателя всплывает над кнопкой эфира. */
    private fun floatReaction(name: String, e: String) {
        if (!CaptureService.status.running || floats.childCount > 20) return
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        v.addView(label(e, 30f).apply { includeFontPadding = true })
        v.addView(label(name.take(12), 11f, Ui.DIM, Ui.bold))
        tileReact.text = "${CaptureService.reactionCount}"
        val lp = android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = (floats.width * (0.25 + Math.random() * 0.45)).toInt(); lp.topMargin = (floats.height * 0.5).toInt()
        floats.addView(v, lp)
        v.alpha = 0f; v.scaleX = 0.6f; v.scaleY = 0.6f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(-px(40).toFloat()).setDuration(260).withEndAction {
            v.animate().alpha(0f).translationY(-px(190).toFloat()).setDuration(1900).withEndAction { floats.removeView(v) }.start()
        }.start()
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

    /** Рок: экран вздрагивает на ударе. */
    private fun shake() {
        val v = scroll.getChildAt(0) ?: return
        if (v.animation != null) return
        val d = px(4).toFloat()
        v.animate().cancel()
        v.translationX = (if (Math.random() < 0.5) -d else d); v.translationY = d / 2
        v.animate().translationX(0f).translationY(0f).setDuration(160).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
    }

    private fun fitOneLine(tv: TextView, maxSp: Float = 28f, minSp: Float = 12f) {
        val avail = tv.width - tv.paddingLeft - tv.paddingRight
        if (avail <= 0) return
        val p = android.graphics.Paint(tv.paint)
        var sp = maxSp
        while (sp > minSp) {
            p.textSize = sp * resources.displayMetrics.scaledDensity
            if (p.measureText(tv.text.toString()) <= avail) break
            sp -= 1f
        }
        tv.textSize = sp
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
