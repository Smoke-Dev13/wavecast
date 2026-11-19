package dev.smoke.wavecast

import android.content.res.AssetManager
import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Аудиоприёмник: куда CaptureService отдаёт PCM. */
interface Sink {
    fun format(json: String)
    fun audio(chunk: ByteArray)
    fun close()
}

/**
 * Минимальный HTTP + WebSocket сервер без зависимостей.
 *   GET /        веб-плеер из assets
 *   GET /cast.html  страница P2P-ведущего
 *   GET /status  JSON
 *   WS  /ws      поток PCM
 *
 * Запросы на вход: слушатель шлёт {"type":"join","name","cid"}. Звук получают только одобренные.
 * Неодобренному отвечаем waiting и зовём onRequest; решение — decide(). Пущенные и отклонённые cid
 * помним до конца эфира. Служебное подключение P2P-страницы (127.0.0.1 + токен) пускается сразу.
 */
class LocalServer(
    private val port: Int,
    private val assets: AssetManager,
    private val onListeners: (Int) -> Unit,
    private val onRequest: (id: String, name: String) -> Unit = { _, _ -> },
    private val onRequestGone: (id: String) -> Unit = {},
    private val onPeople: (List<Pair<String, String>>) -> Unit = {},
    private val onReaction: (name: String, emoji: String) -> Unit = { _, _ -> },
    private val onChat: (id: String, name: String, text: String, nonce: String) -> Unit = { _, _, _, _ -> },
) : Sink {

    /** Токен для служебного подключения P2P-страницы. */
    val internalToken: String = java.util.UUID.randomUUID().toString().replace("-", "")
    @Volatile var autoAllow = false
        set(v) { field = v; if (v) clients.filter { it.pendingName != null }.forEach { decide(it.id, true) } }

    private val clients = ConcurrentHashMap.newKeySet<Client>()
    private val allowed = ConcurrentHashMap.newKeySet<String>()
    private val denied = ConcurrentHashMap.newKeySet<String>()
    private val nextId = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var formatMsg: String? = null
    @Volatile private var trackMsg: String? = null
    @Volatile private var historyMsg: String? = null
    @Volatile private var running = true
    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }

    init {
        thread(name = "wc-accept", isDaemon = true) {
            while (running) {
                val s = try { server.accept() } catch (e: SocketException) { break }
                thread(name = "wc-conn", isDaemon = true) { handle(s) }
            }
        }
    }

    /** Слушатели без служебного подключения P2P-страницы (оно приходит с 127.0.0.1). */
    val count: Int get() = clients.count { !it.internal && it.approved }

    override fun format(json: String) {
        formatMsg = json
        clients.forEach { it.text(json) }
    }

    override fun audio(chunk: ByteArray) {
        clients.forEach { if (it.approved) it.binary(chunk) }
    }

    /** Кто сейчас слушает: (id, имя). */
    fun people(): List<Pair<String, String>> = clients.filter { it.approved && !it.internal }.map { it.id to it.name }

    /** Отключить слушателя: больше не пустим до конца эфира. */
    fun kick(id: String) {
        val c = clients.firstOrNull { it.id == id && !it.internal } ?: return
        denied.add(c.cid)
        c.approved = false
        c.text("""{"type":"kicked"}""")
        c.finish()                                   // сначала уйдёт «kicked», потом закроется соединение
        broadcastStatus()
    }

    /** Сообщение чата (готовый JSON с type=chat) — всем пущенным. */
    fun chatOut(json: String) = clients.forEach { if (it.approved && !it.internal) it.text(json) }

    /** История чата для новичков: JSON-массив сообщений. */
    fun history(itemsJson: String) {
        historyMsg = """{"type":"chat-history","items":$itemsJson}"""
    }

    /** Что играет: JSON {"type":"track",...} или null — убрать. */
    fun track(json: String?) {
        trackMsg = json
        val msg = json ?: """{"type":"track","title":null}"""
        clients.forEach { if (it.approved && !it.internal) it.text(msg) }
    }

    /** Ответ ведущего на запрос; id — тот, что пришёл в onRequest. */
    fun decide(id: String, allow: Boolean) {
        val c = clients.firstOrNull { it.id == id && it.pendingName != null } ?: return
        c.pendingName = null
        (if (allow) allowed else denied).add(c.cid)
        if (allow) approve(c) else { c.text("""{"type":"denied"}""") }
        onRequestGone(id)
    }

    private fun approve(c: Client) {
        c.approved = true
        c.text("""{"type":"approved"}""")
        formatMsg?.let { c.text(it) }
        trackMsg?.let { c.text(it) }
        historyMsg?.let { c.text(it) }
        broadcastStatus()
    }

    private fun onText(c: Client, json: String) {
        if (c.internal) return
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
        when (o.optString("type")) {
            "join" -> if (!c.approved) onJoin(c, o)
            "react" -> if (c.approved) onReact(c, o.optString("e"))
            "chat" -> if (c.approved) {
                val text = o.optString("text").replace(Regex("\\s+"), " ").trim().take(200)
                val now = System.currentTimeMillis()
                if (text.isNotEmpty() && now - c.lastChat >= 1000) {   // не чаще раза в секунду
                    c.lastChat = now
                    onChat(c.id, c.name, text, o.optString("n").take(24))
                }
            }
        }
    }

    private fun onReact(c: Client, e: String) {
        if (e !in EMOJI) return
        val now = System.currentTimeMillis()
        if (now - c.lastReact < 600) return
        c.lastReact = now
        onReaction(c.name, e)
        val msg = org.json.JSONObject().put("type", "react").put("e", e).put("name", c.name).toString()
        clients.forEach { if (it !== c && it.approved && !it.internal) it.text(msg) }
    }

    private fun onJoin(c: Client, o: org.json.JSONObject) {
        val name = o.optString("name").filter { it >= ' ' && it != '<' && it != '>' }.trim().take(24).ifEmpty { "Гость" }
        c.name = name
        c.cid = o.optString("cid").filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(32).ifEmpty { c.id }
        when {
            c.cid in denied -> c.text("""{"type":"denied"}""")   // отклонённых и отключённых не пускаем даже в режиме «всех»
            autoAllow || c.cid in allowed -> approve(c)
            c.pendingName != null -> c.text("""{"type":"waiting"}""")
            clients.count { it.pendingName != null } >= MAX_PENDING -> c.text("""{"type":"busy"}""")
            else -> { c.pendingName = name; c.text("""{"type":"waiting"}"""); onRequest(c.id, name) }
        }
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        clients.forEach { it.shutdown() }
        clients.clear()
    }

    private fun statusJson() = """{"type":"status","live":true,"listeners":$count}"""

    private fun broadcastStatus() {
        val s = statusJson()
        clients.forEach { it.text(s) }
        onListeners(count)
        onPeople(people())
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            val input = BufferedInputStream(sock.getInputStream())
            val reqLine = readLine(input) ?: return sock.close()
            val headers = HashMap<String, String>()
            while (true) {
                val l = readLine(input) ?: return sock.close()
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val target = reqLine.split(" ").getOrNull(1) ?: "/"
            val path = target.substringBefore('?')
            val token = Regex("[?&]t=([A-Za-z0-9]+)").find(target)?.groupValues?.get(1)
            val out = sock.getOutputStream()
            val wsKey = headers["sec-websocket-key"]
            when {
                path == "/ws" && wsKey != null -> upgrade(sock, input, out, wsKey,
                    sock.inetAddress.isLoopbackAddress && token == internalToken)
                path == "/" || path == "/index.html" -> {
                    val body = assets.open("index.html").use { it.readBytes() }
                    respond(out, "200 OK", "text/html; charset=utf-8", body); sock.close()
                }
                path == "/cast.html" -> {   // страница P2P-ведущего для скрытого WebView
                    val body = assets.open("cast.html").use { it.readBytes() }
                    respond(out, "200 OK", "text/html; charset=utf-8", body); sock.close()
                }
                path == "/status" -> {
                    respond(out, "200 OK", "application/json", statusJson().toByteArray()); sock.close()
                }
                else -> { respond(out, "404 Not Found", "text/plain", "not found".toByteArray()); sock.close() }
            }
        } catch (e: Exception) {
            Log.d(TAG, "conn error: $e")
            runCatching { sock.close() }
        }
    }

    private fun respond(out: OutputStream, status: String, type: String, body: ByteArray) {
        out.write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun upgrade(sock: Socket, input: InputStream, out: OutputStream, key: String, internal: Boolean) {
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray())
        )
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
        out.flush()
        val c = Client(sock, out, internal, "w" + nextId.incrementAndGet())
        c.approved = internal
        clients.add(c)
        c.text(statusJson())
        if (internal) formatMsg?.let { c.text(it) }
        try {
            // Читаем входящие кадры только чтобы поймать close/ping
            while (running) {
                val b0 = input.read(); if (b0 < 0) break
                val b1 = input.read(); if (b1 < 0) break
                val opcode = b0 and 0x0F
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
                else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
                if (len > 1_000_000) break
                val mask = if ((b1 and 0x80) != 0) ByteArray(4).also { readFully(input, it) } else null
                val payload = ByteArray(len.toInt()).also { readFully(input, it) }
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode) {
                    0x1 -> onText(c, String(payload, Charsets.UTF_8))
                    0x8 -> break
                    0x9 -> c.frame(0xA, payload)
                }
            }
        } catch (_: Exception) {
        } finally {
            c.shutdown()
            clients.remove(c)
            if (c.pendingName != null) { c.pendingName = null; onRequestGone(c.id) }
            if (running && c.approved) broadcastStatus()
        }
    }

    private class Client(private val sock: Socket, private val out: OutputStream, val internal: Boolean, val id: String) {
        @Volatile var approved = false
        @Volatile var pendingName: String? = null
        @Volatile var cid: String = id
        @Volatile var name: String = "Гость"
        @Volatile var lastReact = 0L
        @Volatile var lastChat = 0L
        private val q = LinkedBlockingQueue<Pair<Int, ByteArray>>()
        @Volatile private var alive = true
        private val writer = thread(name = "wc-write", isDaemon = true) {
            try {
                while (alive) {
                    val (op, data) = q.take()
                    if (op < 0) break
                    writeFrame(op, data)
                }
            } catch (_: Exception) {
            } finally { runCatching { sock.close() } }
        }

        fun text(s: String) = frame(0x1, s.toByteArray())
        fun binary(b: ByteArray) {
            // Медленный клиент: держим не больше ~1.5 с аудио, остальное выкидываем
            while (q.size > MAX_QUEUE) q.poll()
            frame(0x2, b)
        }
        fun frame(op: Int, data: ByteArray) { if (alive) q.offer(op to data) }

        /** Закрыть после отправки всего, что уже в очереди. */
        fun finish() { if (alive) q.offer(-1 to ByteArray(0)) }

        fun shutdown() {
            if (!alive) return
            alive = false
            q.clear(); q.offer(-1 to ByteArray(0))
            runCatching { sock.close() }
        }

        private fun writeFrame(op: Int, data: ByteArray) {
            val n = data.size
            val hdr = when {
                n < 126 -> byteArrayOf((0x80 or op).toByte(), n.toByte())
                n < 65536 -> byteArrayOf((0x80 or op).toByte(), 126, (n shr 8).toByte(), n.toByte())
                else -> ByteArray(10).also {
                    it[0] = (0x80 or op).toByte(); it[1] = 127
                    for (i in 0 until 8) it[9 - i] = (n.toLong() shr (8 * i)).toByte()
                }
            }
            out.write(hdr); out.write(data); out.flush()
        }
    }

    companion object {
        private const val TAG = "WaveCast"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_QUEUE = 40
        private const val MAX_PENDING = 5
        val EMOJI = setOf("🔥", "❤️", "😂", "👏", "🎉", "😍")
        private val LAN_IFACES = listOf("wlan", "swlan", "ap", "softap", "wifi", "rndis", "usb", "ncm", "eth", "bt-pan", "p2p")

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
                if (sb.length > 8192) return null
            }
        }

        private fun readFully(input: InputStream, buf: ByteArray) {
            var off = 0
            while (off < buf.size) {
                val r = input.read(buf, off, buf.size - off)
                if (r < 0) throw java.io.EOFException()
                off += r
            }
        }

        /** Все IPv4-адреса телефона в локальных сетях (Wi-Fi, точка доступа, USB-модем). */
        fun localAddresses(): List<String> =
            NetworkInterface.getNetworkInterfaces().toList()
                // только Wi-Fi, точка доступа, USB/Bluetooth-модем и Ethernet; мобильный интернет (rmnet, ccmni…)
                // даёт «внутренний» адрес оператора, до которого из соседних устройств не достучаться
                .filter { it.isUp && !it.isLoopback && LAN_IFACES.any { p -> it.name.startsWith(p) } }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
                .distinct()
    }
}
