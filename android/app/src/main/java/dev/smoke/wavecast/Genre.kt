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

    // приписки, которые YouTube и плееры добавляют к названию: (Official Live Video), [Lyrics], (Remastered 2011)…
    private val junk = Regex("""\s*[(\[][^)\]]*(official|video|audio|lyric|visuali[sz]er|remaster|live|\bhd\b|4k|mv|клип|премьер|текст)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val feat = Regex("""\s+[(\[]?(ft\.|feat\.|featuring|prod\.)\s.*$""", RegexOption.IGNORE_CASE)
    // приписка после дефиса у Spotify и др.: «- Remastered 2011», «- Live», «- Radio Edit», «- 2019 Remaster», «- Acoustic Version»…
    private val suffix = Regex("""(?i)((\d{4}\s+)?(re-?master(ed)?|live|radio edit|edit|single version|album version|version|mix|remix|mono|stereo|demo|acoustic|instrumental|extended|bonus track|deluxe|original mix|from .*|feat\..*|ft\..*|live at .*|live from .*|\d{4})(\s+(\d{4}|version|edit|mix|remaster(ed)?))*)""")
    private val channel = Regex("""\s*(-\s*topic|vevo|official|music|records|tv)$""", RegexOption.IGNORE_CASE)

    /**
     * Чистые «исполнитель» и «название» для экрана и поиска жанра.
     * «Eths - Crucifère (Official Live Video)» от канала «Season of Mist» → «Eths», «Crucifère».
     */
    fun clean(artist: String?, title: String): Pair<String, String> {
        var t = title.replace(junk, "").replace(feat, "").trim()
        var a = (artist ?: "").replace(channel, "").trim()
        val parts = t.split(" - ", " – ", " — ", limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            if (suffix.matches(parts[1].trim())) t = parts[0].trim()   // «Песня - Remastered 2011» (Spotify): это приписка, исполнитель не трогаем
            else { a = parts[0].trim(); t = parts[1].trim() }          // «Исполнитель - Песня» (YouTube): исполнитель отсюда точнее, чем канал или лейбл
        }
        return a.take(120) to t.ifBlank { title }.take(120)
    }

    /** Ищет жанр в фоне; [done] вызывается в фоновом потоке (пустая строка — не нашли). */
    fun lookup(artist: String, title: String, done: (String) -> Unit) {
        val main = artist.split(",", " & ", " feat", " ft.", " x ", " X ").first().trim()
        val key = "$main|$title".lowercase()
        synchronized(cache) { cache[key] }?.let { done(it); return }
        pool.execute {
            var g = ""; var failed = false
            // 1) исполнитель + песня, 2) только песня, 3) сам исполнитель — у него в каталоге тоже есть жанр
            val tries = listOf("$main $title".trim() to "song", title to "song", main to "musicArtist").filter { it.first.isNotBlank() }.distinct()
            for ((q, entity) in tries) {
                val r = runCatching { itunes(q, entity) }
                if (r.isFailure) { failed = true; continue }
                g = r.getOrDefault("")
                if (themeOf(g) != null) break
            }
            if (themeOf(g) != null || !failed) synchronized(cache) { cache[key] = g }   // сбой сети не запоминаем — спросим снова
            done(g)
        }
    }

    private fun itunes(q: String, entity: String): String {
        val url = URL("https://itunes.apple.com/search?media=music&entity=$entity&limit=3&term=" + URLEncoder.encode(q, "UTF-8"))
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000
        c.setRequestProperty("User-Agent", "WaveCast")
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val res = JSONObject(body).optJSONArray("results") ?: return ""
            for (i in 0 until res.length()) {
                val g = res.getJSONObject(i).optString("primaryGenreName")
                if (g.isNotBlank()) return g.take(60)
            }
            return ""
        } finally { c.disconnect() }
    }
}
