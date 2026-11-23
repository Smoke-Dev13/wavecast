package dev.smoke.wavecast

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * P2P-вещание: скрытый WebView открывает cast.html с локального сервера приложения.
 * Страница забирает PCM по ws://127.0.0.1 и раздаёт его слушателям напрямую через WebRTC
 * (Opus-кодирование и обход NAT делает движок браузера). Создавать и закрывать на главном потоке.
 */
@SuppressLint("SetJavaScriptEnabled")
class P2PHost(
    ctx: Context,
    port: Int,
    service: String,
    room: String,
    key: String,
    token: String,
    auto: Boolean,
    private val stateCb: (connected: Boolean, listeners: Int, error: String?) -> Unit,
    private val requestCb: (id: String, name: String) -> Unit,
    private val requestGoneCb: (id: String) -> Unit,
    private val peopleCb: (List<Pair<String, String>>) -> Unit = {},
    private val reactionCb: (name: String, emoji: String) -> Unit = { _, _ -> },
    private val chatCb: (id: String, name: String, text: String, nonce: String) -> Unit = { _, _, _, _ -> },
) {
    private val main = Handler(Looper.getMainLooper())
    private val web = WebView(ctx.applicationContext)

    init {
        web.settings.javaScriptEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false   // AudioContext без нажатия
        web.addJavascriptInterface(Bridge(), "WaveCastHost")
        val src = "ws://127.0.0.1:$port/ws?t=$token"
        val hash = "signal=${Uri.encode(service)}&room=${Uri.encode(room)}&key=${Uri.encode(key)}" +
            "&src=${Uri.encode(src)}&auto=${if (auto) 1 else 0}"
        web.loadUrl("http://127.0.0.1:$port/cast.html#$hash")
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onState(json: String) {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return
            val err = if (o.isNull("error")) null else o.optString("error")
            val arr = o.optJSONArray("people")
            val people = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                arr!!.optJSONObject(i)?.let { it.optString("id") to it.optString("name", "Гость") }
            }
            main.post { stateCb(o.optBoolean("signal"), o.optInt("listeners"), err); peopleCb(people) }
        }

        @JavascriptInterface
        fun onReaction(name: String, emoji: String) { main.post { reactionCb(name, emoji) } }

        @JavascriptInterface
        fun onChat(id: String, name: String, text: String, nonce: String) { main.post { chatCb(id, name, text, nonce) } }

        @JavascriptInterface
        fun onRequest(id: String, name: String) { main.post { requestCb(id, name) } }

        @JavascriptInterface
        fun onRequestGone(id: String) { main.post { requestGoneCb(id) } }
    }

    /** Ответ ведущего на запрос слушателя. */
    fun decide(id: String, allow: Boolean) {
        main.post { web.evaluateJavascript("wcDecide(${JSONObject.quote(id)}, $allow)", null) }
    }

    /** Готовое сообщение чата (JSON-объект) — всем слушателям по ссылке. */
    fun chatOut(json: String) {
        main.post { web.evaluateJavascript("wcChatOut($json)", null) }
    }

    fun history(itemsJson: String) {
        main.post { web.evaluateJavascript("wcHistory($itemsJson)", null) }
    }

    fun kick(id: String) {
        main.post { web.evaluateJavascript("wcKick(${JSONObject.quote(id)})", null) }
    }

    /** Что играет: JSON-объект {title, artist, cover} или null. */
    fun track(json: String?) {
        main.post { web.evaluateJavascript("wcTrack(${json ?: "null"})", null) }
    }

    fun setAuto(on: Boolean) {
        main.post { web.evaluateJavascript("wcAuto($on)", null) }
    }

    fun close() {
        main.removeCallbacksAndMessages(null)
        web.removeJavascriptInterface("WaveCastHost")
        web.loadUrl("about:blank")
        web.destroy()
    }

    companion object {
        fun listenUrl(service: String, room: String) = normalize(service) + "/r/" + room

        /** "host", "http://host/", "https://host/r/x" → "https://host" */
        fun normalize(service: String): String {
            var u = service.trim()
            if (!u.contains("://")) u = "https://$u"
            val host = Regex("^https?://([^/:?#]+)").find(u)?.groupValues?.get(1).orEmpty()
            val local = host == "localhost" || Regex("^(127|10|192\\.168|172\\.(1[6-9]|2\\d|3[01]))\\.").containsMatchIn(host)
            if (!local) u = u.replaceFirst(Regex("^http://"), "https://")
            return Regex("^(https?://[^/?#]+)").find(u)?.value ?: u
        }
    }
}
