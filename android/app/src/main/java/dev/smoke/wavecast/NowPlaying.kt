package dev.smoke.wavecast

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Пустой слушатель уведомлений: Android даёт читать «что сейчас играет» в других плеерах
 * только приложениям с доступом к уведомлениям. Сами уведомления мы не читаем.
 */
class NowPlayingListener : NotificationListenerService()

/**
 * Следит за плеерами (Spotify, YouTube, ВК, Яндекс Музыка…) и сообщает, что играет:
 * {"title","artist","cover":"data:image/jpeg;base64,…"} или null. Мессенджеры и сам WaveCast пропускаем.
 */
class NowPlaying(private val ctx: Context, private val onChange: (title: String?, artist: String?, json: String?) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(ctx, NowPlayingListener::class.java)
    private val watched = mutableMapOf<MediaController, MediaController.Callback>()
    private var lastKey: String? = null
    private var started = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> watch(list ?: emptyList()) }

    fun start() {
        if (started || !allowed(ctx)) return
        started = runCatching {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, component, main)
            watch(msm.getActiveSessions(component))
        }.isSuccess
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsChanged) }
        watched.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        watched.clear()
        main.removeCallbacksAndMessages(null)
    }

    private fun watch(list: List<MediaController>) {
        val keep = list.filter { it.packageName != ctx.packageName && it.packageName !in CaptureService.MESSENGERS }
        watched.keys.filter { it !in keep }.forEach { c -> watched.remove(c)?.let { runCatching { c.unregisterCallback(it) } } }
        keep.filter { it !in watched }.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = schedule()
                override fun onPlaybackStateChanged(state: PlaybackState?) = schedule()
                override fun onSessionDestroyed() = schedule()
            }
            runCatching { c.registerCallback(cb, main); watched[c] = cb }
        }
        schedule()
    }

    // плееры шлют несколько событий подряд — собираем их в одно
    private fun schedule() {
        main.removeCallbacks(update)
        main.postDelayed(update, 400)
    }

    private val update = Runnable {
        val playing = watched.keys.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val md = playing?.metadata
        val title = md?.let { it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) }
            ?.trim()?.take(120)
        if (title.isNullOrEmpty()) { publish(null, null, null); return@Runnable }
        val artist = (md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))?.trim()?.take(120)
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val key = "$title|$artist|${art?.generationId}"
        if (key == lastKey) return@Runnable
        lastKey = key
        val o = JSONObject().put("title", title).put("artist", artist ?: "")
        cover(art)?.let { o.put("cover", it) }
        publish(title, artist, o.toString())
    }

    private fun publish(title: String?, artist: String?, json: String?) {
        if (json == null) { if (lastKey == null) return; lastKey = null }
        onChange(title, artist, json)
    }

    /** Обложка → JPEG 256×256 максимум, ~10–20 КБ в base64: влезает в одно сообщение. */
    private fun cover(b: Bitmap?): String? = runCatching {
        if (b == null || b.width <= 0) return null
        val k = 256f / maxOf(b.width, b.height)
        val small = if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true) else b
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 75, out)
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    companion object {
        fun allowed(ctx: Context): Boolean =
            ctx.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(ctx, NowPlayingListener::class.java))
    }
}
