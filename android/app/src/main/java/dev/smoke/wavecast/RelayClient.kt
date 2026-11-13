package dev.smoke.wavecast

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Отправляет поток на relay-сервер, переподключается сам. */
class RelayClient(
    baseUrl: String,
    key: String,
    private val onState: (connected: Boolean, listeners: Int, error: String?) -> Unit,
) : Sink {

    private val url: String = buildUrl(baseUrl, key)
    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var ws: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var closed = false
    @Volatile private var formatMsg: String? = null
    private var backoff = 1000L

    init { connect() }

    private fun connect() {
        if (closed) return
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                backoff = 1000L
                formatMsg?.let { webSocket.send(it) }
                onState(true, 0, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val o = JSONObject(text)
                    if (o.optString("type") == "listeners") onState(true, o.optInt("count"), null)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, "закрыто: $code $reason")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = when (response?.code) {
                    401 -> "неверный ключ"
                    null -> t.message ?: t.javaClass.simpleName
                    else -> "HTTP ${response.code}"
                }
                lost(webSocket, why)
            }
        })
    }

    private fun lost(socket: WebSocket, why: String) {
        if (socket !== ws) return
        connected = false
        if (closed) return
        onState(false, 0, why)
        handler.postDelayed({ connect() }, backoff)
        backoff = (backoff * 2).coerceAtMost(15_000L)
    }

    override fun format(json: String) {
        formatMsg = json
        if (connected) ws?.send(json)
    }

    override fun audio(chunk: ByteArray) {
        val s = ws ?: return
        if (!connected) return
        // Плохой канал: не копим очередь больше ~2 с, иначе задержка растёт бесконечно
        if (s.queueSize() > MAX_QUEUE_BYTES) return
        s.send(chunk.toByteString())
    }

    override fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        ws?.close(1000, "stop")
        http.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val MAX_QUEUE_BYTES = 384_000L

        /**
         * Принимает любую из форм:
         *   https://host                 → wss://host/ingest           (комната main)
         *   https://host/r/party         → wss://host/ingest/party
         *   wss://host/ingest/party?key= → как есть (ссылка из админ-API)
         */
        fun buildUrl(base: String, key: String): String {
            var u = base.trim().trimEnd('/')
            if (!u.contains("://")) u = "https://$u"
            u = u.replaceFirst(Regex("^http://"), "ws://").replaceFirst(Regex("^https://"), "wss://")
            if (u.contains("/ingest") && u.contains("key=")) return u
            val m = Regex("^(wss?://[^/?#]+)(?:/r/([^/?#]+))?").find(u) ?: return u
            val host = m.groupValues[1]
            val room = m.groupValues[2]
            return "$host/ingest" + (if (room.isNotEmpty()) "/$room" else "") +
                "?key=" + URLEncoder.encode(key, "UTF-8")
        }
    }
}
