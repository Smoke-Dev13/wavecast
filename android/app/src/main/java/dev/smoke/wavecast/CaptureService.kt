package dev.smoke.wavecast

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Foreground-сервис: захватывает звук всех приложений (AudioPlaybackCapture)
 * и раздаёт его слушателям: напрямую по ссылке (P2P) и в локальную сеть.
 */
class CaptureService : Service() {

    data class Status(
        val running: Boolean = false,
        val localUrls: List<String> = emptyList(),
        val localListeners: Int = 0,
        val p2pUrl: String? = null,          // ссылка для слушателей
        val p2pConnected: Boolean = false,   // есть связь с сигнальным сервисом
        val p2pListeners: Int = 0,
        val p2pError: String? = null,
        val requests: List<Request> = emptyList(),   // кто просится в эфир
        val autoAllow: Boolean = false,
        val error: String? = null,
    )

    /** Запрос на вход. key: "p:<id>" — по ссылке, "w:<id>" — из Wi-Fi сети. */
    data class Request(val key: String, val name: String) {
        val viaWifi get() = key.startsWith("w:")
    }

    companion object {
        const val ACTION_START = "dev.smoke.wavecast.START"
        const val ACTION_STOP = "dev.smoke.wavecast.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_LOCAL = "local"
        const val EXTRA_PORT = "port"
        const val EXTRA_SERVICE = "service"
        const val EXTRA_ROOM = "room"
        const val EXTRA_HOST_KEY = "hostKey"
        const val EXTRA_AUTO = "auto"
        const val EXTRA_MUTE_MESSENGERS = "muteMessengers"

        /** Мессенджеры: их звук (голосовые, кружки) в эфир не пускаем. Музыкальные приложения не трогаем. */
        val MESSENGERS = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus", "org.thunderdog.challegram", "com.whatsapp", "com.whatsapp.w4b", "com.viber.voip", "org.thoughtcrime.securesms", "com.vk.im", "ru.oneme.app", "jp.naver.line.android", "com.facebook.orca", "com.snapchat.android")
        const val ACTION_DECIDE = "dev.smoke.wavecast.DECIDE"
        const val ACTION_AUTO = "dev.smoke.wavecast.AUTO"
        const val EXTRA_REQ = "req"
        const val EXTRA_ALLOW = "allow"

        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        private const val CHUNK_MS = 40
        private const val CHANNEL_ID = "wavecast"
        private const val NOTIF_ID = 1
        private const val REQ_CHANNEL_ID = "wavecast_requests"
        private const val TAG = "WaveCast"

        private val main = Handler(Looper.getMainLooper())
        @Volatile var status = Status(); private set
        /** Громкость эфира 0..1 — для пульсации кнопки на главном экране. */
        @Volatile var level = 0f; private set
        var listener: ((Status) -> Unit)? = null

        private fun publish(s: Status) {
            status = s
            main.post { listener?.invoke(s) }
        }
    }

    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    @Volatile private var capturing = false
    private val sinks = CopyOnWriteArrayList<Sink>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var p2p: P2PHost? = null
    private var local: LocalServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
            ACTION_START -> if (!capturing) start(intent)
            ACTION_DECIDE -> decide(intent.getStringExtra(EXTRA_REQ).orEmpty(), intent.getBooleanExtra(EXTRA_ALLOW, false))
            ACTION_AUTO -> setAuto(intent.getBooleanExtra(EXTRA_AUTO, false))
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        // Android 14: startForeground с типом mediaProjection строго ДО getMediaProjection()
        startForeground(NOTIF_ID, buildNotification("Запуск…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = data?.let { mpm.getMediaProjection(code, it) }
        if (mp == null) { fail("Нет разрешения на захват"); return }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopAll() } }
        }, main)

        val auto = intent.getBooleanExtra(EXTRA_AUTO, false)
        var st = Status(running = true, autoAllow = auto)
        publish(st)

        // Локальный сервер нужен всегда: он раздаёт звук по Wi-Fi и кормит P2P-страницу
        val want = intent.getIntExtra(EXTRA_PORT, 8080)
        val srv = (want until want + 10).firstNotNullOfOrNull { p ->
            runCatching {
                p to LocalServer(p, assets,
                    onListeners = { n -> publish(status.copy(localListeners = n)); updateNotification() },
                    onRequest = { id, name -> main.post { addRequest(Request("w:$id", name)) } },
                    onRequestGone = { id -> main.post { removeRequest("w:$id") } })
            }.getOrNull()
        }
        if (srv == null) { fail("Порты $want–${want + 9} заняты"); return }
        val port = srv.first
        local = srv.second.also { it.autoAllow = auto }
        sinks += srv.second
        if (intent.getBooleanExtra(EXTRA_LOCAL, true)) {
            st = st.copy(localUrls = LocalServer.localAddresses().map { "http://$it:$port" })
        }

        val service = intent.getStringExtra(EXTRA_SERVICE).orEmpty()
        if (service.isNotBlank()) {
            val room = intent.getStringExtra(EXTRA_ROOM).orEmpty()
            val base = P2PHost.normalize(service)
            st = st.copy(p2pUrl = P2PHost.listenUrl(service, room))
            p2p = P2PHost(this, port, base, room, intent.getStringExtra(EXTRA_HOST_KEY).orEmpty(), srv.second.internalToken, auto,
                stateCb = { ok, n, err ->
                    publish(status.copy(p2pConnected = ok, p2pListeners = n, p2pError = err))
                    updateNotification()
                },
                requestCb = { id, name -> addRequest(Request("p:$id", name)) },
                requestGoneCb = { id -> removeRequest("p:$id") })
        }

        val muted = if (intent.getBooleanExtra(EXTRA_MUTE_MESSENGERS, true)) messengerUids() else emptyList()
        val rec = try { buildRecorder(mp, muted) } catch (e: Exception) { fail("AudioRecord: ${e.message}"); return }
        recorder = rec

        acquireLocks()
        publish(st.copy(requests = status.requests))
        updateNotification()
        capturing = true
        thread(name = "wc-capture", priority = Thread.MAX_PRIORITY) { captureLoop(rec) }
    }

    // ---- запросы на вход ----

    private fun addRequest(r: Request) {
        if (!capturing && recorder == null) return
        publish(status.copy(requests = status.requests.filter { it.key != r.key } + r))
        requestNotification(r)
    }

    private fun removeRequest(key: String) {
        getSystemService(NotificationManager::class.java).cancel(notifId(key))
        publish(status.copy(requests = status.requests.filter { it.key != key }))
    }

    private fun decide(key: String, allow: Boolean) {
        val id = key.substringAfter(':')
        if (key.startsWith("p:")) p2p?.decide(id, allow) else local?.decide(id, allow)
        removeRequest(key)
    }

    private fun setAuto(on: Boolean) {
        p2p?.setAuto(on)
        local?.autoAllow = on
        publish(status.copy(autoAllow = on))
        if (on) status.requests.forEach { removeRequest(it.key) }
    }

    private fun notifId(key: String) = 1000 + (key.hashCode() and 0xFFFF)

    private fun requestNotification(r: Request) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(REQ_CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(REQ_CHANNEL_ID, "Запросы на вход", NotificationManager.IMPORTANCE_HIGH))
        }
        fun action(allow: Boolean) = PendingIntent.getService(
            this, notifId(r.key) * 2 + (if (allow) 1 else 0),
            Intent(this, CaptureService::class.java).setAction(ACTION_DECIDE)
                .putExtra(EXTRA_REQ, r.key).putExtra(EXTRA_ALLOW, allow),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, REQ_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wave)
            .setContentTitle("${r.name} хочет подключиться")
            .setContentText(if (r.viaWifi) "Из твоей Wi-Fi сети" else "По ссылке на эфир")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Пустить", action(true)).build())
            .addAction(Notification.Action.Builder(null, "Отклонить", action(false)).build())
            .build()
        nm.notify(notifId(r.key), n)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO запрашивается в MainActivity
    /** UID установленных мессенджеров из списка MESSENGERS. */
    private fun messengerUids(): List<Int> = MESSENGERS.mapNotNull { pkg ->
        runCatching { packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
    }.distinct()

    private fun buildRecorder(mp: MediaProjection, excludeUids: List<Int>): AudioRecord {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .apply { excludeUids.forEach { excludeUid(it) } }
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min * 4, chunkBytes() * 8))
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "не инициализирован" }
        return rec
    }

    private fun chunkBytes() = SAMPLE_RATE * CHUNK_MS / 1000 * CHANNELS * 2

    private fun captureLoop(rec: AudioRecord) {
        val fmtJson = """{"type":"format","codec":"pcm_s16le","rate":$SAMPLE_RATE,"channels":$CHANNELS}"""
        sinks.forEach { it.format(fmtJson) }
        val size = chunkBytes()
        try {
            rec.startRecording()
            while (capturing) {
                val buf = ByteArray(size)
                var off = 0
                while (off < size && capturing) {
                    val r = rec.read(buf, off, size - off)
                    if (r < 0) throw IllegalStateException("read=$r")
                    off += r
                }
                if (off == size) {
                    level = rms(buf)
                    for (s in sinks) s.audio(buf)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            main.post { fail("Захват остановлен: ${e.message}") }
        }
    }

    /** RMS по каждому 8-му кадру, с запасом усилено до 0..1. */
    private fun rms(b: ByteArray): Float {
        var sum = 0.0; var n = 0
        var i = 0
        while (i + 1 < b.size) {
            val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort() / 32768.0
            sum += v * v; n++; i += 32
        }
        return if (n == 0) 0f else (Math.sqrt(sum / n) * 3.0).coerceAtMost(1.0).toFloat()
    }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        stopAll()
        publish(Status(error = msg))
    }

    private fun stopAll() {
        capturing = false
        level = 0f
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        p2p?.let { runCatching { it.close() } }
        p2p = null
        local = null
        status.requests.forEach { getSystemService(NotificationManager::class.java).cancel(notifId(it.key)) }
        sinks.forEach { runCatching { it.close() } }
        sinks.clear()
        runCatching { projection?.stop() }
        projection = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        if (status.running) publish(Status())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (capturing) stopAll()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WaveCast::capture").apply { acquire() }
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WaveCast").apply { acquire() }
    }

    private fun summary(): String {
        val s = status
        val parts = mutableListOf<String>()
        if (s.p2pUrl != null) parts += if (s.p2pConnected || s.p2pListeners > 0) "по ссылке: ${s.p2pListeners}" else "по ссылке: нет связи"
        if (s.localUrls.isNotEmpty()) parts += "Wi-Fi: ${s.localListeners}"
        return "Слушателей — " + parts.joinToString(", ")
    }

    private fun updateNotification() {
        if (!capturing && recorder == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(summary()))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Трансляция", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wave)
            .setContentTitle("WaveCast в эфире")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Стоп", stop).build())
            .build()
    }
}
