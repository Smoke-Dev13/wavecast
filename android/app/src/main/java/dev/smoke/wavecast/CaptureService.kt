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
 * и раздаёт его в локальную сеть и/или на relay-сервер.
 */
class CaptureService : Service() {

    data class Status(
        val running: Boolean = false,
        val localUrls: List<String> = emptyList(),
        val localListeners: Int = 0,
        val relayEnabled: Boolean = false,
        val relayConnected: Boolean = false,
        val relayListeners: Int = 0,
        val relayError: String? = null,
        val p2pUrl: String? = null,          // ссылка для слушателей
        val p2pConnected: Boolean = false,   // есть связь с сигнальным сервисом
        val p2pListeners: Int = 0,
        val p2pError: String? = null,
        val error: String? = null,
    )

    companion object {
        const val ACTION_START = "dev.smoke.wavecast.START"
        const val ACTION_STOP = "dev.smoke.wavecast.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_LOCAL = "local"
        const val EXTRA_PORT = "port"
        const val EXTRA_RELAY_URL = "relayUrl"
        const val EXTRA_RELAY_KEY = "relayKey"
        const val EXTRA_SERVICE = "service"
        const val EXTRA_ROOM = "room"
        const val EXTRA_HOST_KEY = "hostKey"

        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        private const val CHUNK_MS = 40
        private const val CHANNEL_ID = "wavecast"
        private const val NOTIF_ID = 1
        private const val TAG = "WaveCast"

        private val main = Handler(Looper.getMainLooper())
        @Volatile var status = Status(); private set
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
            ACTION_START -> if (!capturing) start(intent)
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

        var st = Status(running = true)

        // Локальный сервер нужен всегда: он раздаёт звук по Wi-Fi и кормит P2P-страницу
        val want = intent.getIntExtra(EXTRA_PORT, 8080)
        val srv = (want until want + 10).firstNotNullOfOrNull { p ->
            runCatching { p to LocalServer(p, assets) { n -> publish(status.copy(localListeners = n)); updateNotification() } }.getOrNull()
        }
        if (srv == null) { fail("Порты $want–${want + 9} заняты"); return }
        val port = srv.first
        sinks += srv.second
        if (intent.getBooleanExtra(EXTRA_LOCAL, true)) {
            st = st.copy(localUrls = LocalServer.localAddresses().map { "http://$it:$port" })
        }

        val service = intent.getStringExtra(EXTRA_SERVICE).orEmpty()
        if (service.isNotBlank()) {
            val room = intent.getStringExtra(EXTRA_ROOM).orEmpty()
            val base = P2PHost.normalize(service)
            st = st.copy(p2pUrl = P2PHost.listenUrl(service, room))
            p2p = P2PHost(this, port, base, room, intent.getStringExtra(EXTRA_HOST_KEY).orEmpty()) { ok, n, err ->
                publish(status.copy(p2pConnected = ok, p2pListeners = n, p2pError = err))
                updateNotification()
            }
        }

        val relayUrl = intent.getStringExtra(EXTRA_RELAY_URL).orEmpty()
        if (relayUrl.isNotBlank()) {
            st = st.copy(relayEnabled = true)
            sinks += RelayClient(relayUrl, intent.getStringExtra(EXTRA_RELAY_KEY).orEmpty()) { ok, n, err ->
                publish(status.copy(relayConnected = ok, relayListeners = n, relayError = err))
                updateNotification()
            }
        }

        val rec = try { buildRecorder(mp) } catch (e: Exception) { fail("AudioRecord: ${e.message}"); return }
        recorder = rec

        acquireLocks()
        publish(st)
        updateNotification()
        capturing = true
        thread(name = "wc-capture", priority = Thread.MAX_PRIORITY) { captureLoop(rec) }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO запрашивается в MainActivity
    private fun buildRecorder(mp: MediaProjection): AudioRecord {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
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
                if (off == size) for (s in sinks) s.audio(buf)
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            main.post { fail("Захват остановлен: ${e.message}") }
        }
    }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        stopAll()
        publish(Status(error = msg))
    }

    private fun stopAll() {
        capturing = false
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        p2p?.let { runCatching { it.close() } }
        p2p = null
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
        if (s.relayEnabled) parts += if (s.relayConnected) "Relay: ${s.relayListeners}" else "Relay: нет связи"
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
