package dev.smoke.wavecast

import android.content.Context
import java.security.SecureRandom

/** Все настройки приложения в одном месте. */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("wavecast", Context.MODE_PRIVATE)

    /** Адрес сигнального сервиса без https:// — как показываем пользователю. */
    var service: String
        get() = p.getString("service", null)?.takeIf { it.isNotBlank() }?.let(::clean) ?: DEFAULT_SERVICE
        set(v) = p.edit().putString("service", clean(v).ifBlank { DEFAULT_SERVICE }).apply()

    var room: String
        get() = p.getString("room", null) ?: random(ROOM_CHARS, 12).also { room = it }
        set(v) = p.edit().putString("room", v).apply()

    var autoAllow: Boolean
        get() = p.getBoolean("autoAllow", false)
        set(v) = p.edit().putBoolean("autoAllow", v).apply()

    var muteMessengers: Boolean
        get() = p.getBoolean("muteMessengers", true)
        set(v) = p.edit().putBoolean("muteMessengers", v).apply()

    /** Ключ ведущего: закрепляет название эфира за этим телефоном. */
    val hostKey: String
        get() = p.getString("hostKey", null) ?: random(KEY_CHARS, 32).also { p.edit().putString("hostKey", it).apply() }

    companion object {
        /** Сигнальный сервис по умолчанию — чтобы эфир по ссылке работал без настройки. */
        const val DEFAULT_SERVICE = "wavecast.smokedev13.workers.dev"
        val ROOM_RE = Regex("^[a-z0-9][a-z0-9_-]{0,47}$")
        private const val ROOM_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
        private const val KEY_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        /** "https://host/r/x/" → "host"; http:// оставляем только для локальных адресов. */
        fun clean(v: String): String {
            val n = P2PHost.normalize(v.trim())
            return if (n.startsWith("https://")) n.removePrefix("https://") else n
        }

        fun random(chars: String, n: Int): String {
            val r = SecureRandom()
            return (1..n).map { chars[r.nextInt(chars.length)] }.joinToString("")
        }
    }
}
