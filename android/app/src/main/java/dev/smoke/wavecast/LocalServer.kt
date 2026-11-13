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
 *   GET /status  JSON
 *   WS  /ws      поток PCM
 */
class LocalServer(
    private val port: Int,
    private val assets: AssetManager,
    private val onListeners: (Int) -> Unit,
) : Sink {

    private val clients = ConcurrentHashMap.newKeySet<Client>()
    @Volatile private var formatMsg: String? = null
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

    val count: Int get() = clients.size

    override fun format(json: String) {
        formatMsg = json
        clients.forEach { it.text(json) }
    }

    override fun audio(chunk: ByteArray) {
        clients.forEach { it.binary(chunk) }
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        clients.forEach { it.shutdown() }
        clients.clear()
    }

    private fun statusJson() = """{"type":"status","live":true,"listeners":${clients.size}}"""

    private fun broadcastStatus() {
        val s = statusJson()
        clients.forEach { it.text(s) }
        onListeners(clients.size)
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
            val path = reqLine.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
            val out = sock.getOutputStream()
            val wsKey = headers["sec-websocket-key"]
            when {
                path == "/ws" && wsKey != null -> upgrade(sock, input, out, wsKey)
                path == "/" || path == "/index.html" -> {
                    val body = assets.open("index.html").use { it.readBytes() }
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

    private fun upgrade(sock: Socket, input: InputStream, out: OutputStream, key: String) {
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray())
        )
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
        out.flush()
        val c = Client(sock, out)
        clients.add(c)
        c.text(statusJson())
        formatMsg?.let { c.text(it) }
        broadcastStatus()
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
                    0x8 -> break
                    0x9 -> c.frame(0xA, payload)
                }
            }
        } catch (_: Exception) {
        } finally {
            c.shutdown()
            clients.remove(c)
            if (running) broadcastStatus()
        }
    }

    private class Client(private val sock: Socket, private val out: OutputStream) {
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
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
                .distinct()
    }
}
