package dev.smoke.wavecast

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Жанр трека → тема оформления (у ведущего и у всех слушателей).
 * Жанр берём из плеера, если он его сообщает; иначе спрашиваем открытый каталог iTunes
 * (уходят только «исполнитель + название»). Не нашли — темы нет, остаётся обычный живой неон.
 */
object Genre {

    /** Тема: id для страницы/приложения и короткая метка для людей. */
    data class Theme(val id: String, val label: String)

    // порядок важен: «электропоп» — клуб, «поп-рок» — рок, «хип-хоп» не путаем с «поп», метал — отдельная тёмная тема
    private val RULES = listOf(
        Theme("street", "ХИП-ХОП") to listOf("hip-hop", "hip hop", "hiphop", "rap", "trap", "drill", "grime", "рэп", "хип-хоп"),
        Theme("dark", "МЕТАЛ") to listOf("metal", "doom", "sludge", "stoner", "drone", "goth", "industrial", "death", "black", "метал", "дум"),
        Theme("rock", "РОК") to listOf("rock", "punk", "grunge", "hardcore", "alternative", "emo", "рок", "панк"),
        Theme("club", "ЭЛЕКТРОНИКА") to listOf("electro", "dance", "house", "techno", "edm", "trance", "drum", "dubstep", "bass", "club", "электрон", "техно", "хаус"),
        Theme("night", "R&B") to listOf("r&b", "rnb", "r'n'b", "soul", "funk"),
        Theme("calm", "ЧИЛЛ") to listOf("jazz", "classical", "ambient", "lo-fi", "lofi", "chill", "soundtrack", "piano", "instrumental", "new age", "blues", "джаз", "классик"),
        Theme("warm", "ФОЛК") to listOf("folk", "country", "world", "singer/songwriter", "reggae", "acoustic", "фолк", "этно", "народ", "кантри"),
        Theme("pop", "ПОП") to listOf("pop", "disco", "latin", "reggaeton", "k-pop", "j-pop", "поп", "эстрада", "диско"),
    )

    fun themeOf(genre: String?): Theme? {
        val g = genre?.lowercase()?.trim().orEmpty()
        if (g.isEmpty()) return null
        return RULES.firstOrNull { (_, keys) -> keys.any { g.contains(it) } }?.first
    }

    private val pool = Executors.newSingleThreadExecutor()
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 200
    }

    /** Ищет жанр в фоне; [done] вызывается в фоновом потоке (пустая строка — не нашли). */
    fun lookup(artist: String?, title: String, done: (String) -> Unit) {
        val q = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title).joinToString(" ").take(160)
        synchronized(cache) { cache[q] }?.let { done(it); return }
        pool.execute {
            val g = runCatching { itunes(q) }.getOrDefault("")
            synchronized(cache) { cache[q] = g }
            done(g)
        }
    }

    private fun itunes(q: String): String {
        val url = URL("https://itunes.apple.com/search?media=music&entity=song&limit=3&term=" + URLEncoder.encode(q, "UTF-8"))
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        c.setRequestProperty("User-Agent", "WaveCast")
        return try {
            if (c.responseCode != 200) return ""
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val res = JSONObject(body).optJSONArray("results") ?: return ""
            for (i in 0 until res.length()) {
                val g = res.getJSONObject(i).optString("primaryGenreName")
                if (g.isNotBlank()) return g.take(60)
            }
            ""
        } finally { c.disconnect() }
    }
}
