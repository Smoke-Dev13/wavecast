package dev.smoke.wavecast

/**
 * Характер оформления по жанру (цвета — из обложки, а это — как всё двигается).
 * mv — во сколько раз медленнее плывут пятна, bk — во сколько раз реже мигают,
 * beat — сила вспышки (0 — без вспышек), gap — не чаще одной вспышки за столько мс,
 * fat — толщина волны, strobe — белый стробоскоп, shake — экран вздрагивает,
 * overlay — постоянный цветной слой (огонь у рока, тёплый свет у фолка).
 */
data class Look(
    val mv: Float = 1f, val bk: Float = 1f, val beat: Float = 1f, val gap: Long = 160, val thr: Float = 0.05f,
    val fat: Float = 1f, val strobe: Boolean = false, val shake: Boolean = false,
    val overlay: Int = 0, val overlayTop: Boolean = false,
    /** Цвета темы: подсветка под музыку, а не под обложку. null — цвета из обложки. */
    val palette: IntArray? = null,
)

private fun hsl(h: Float, s: Float, l: Float): Int {
    val c = (1f - Math.abs(2 * l - 1f)) * s; val x = c * (1f - Math.abs((h / 60f) % 2f - 1f)); val m = l - c / 2f
    val (r, g, b) = when ((h / 60f).toInt() % 6) { 0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
    return android.graphics.Color.rgb(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
}
private fun pal(vararg c: Triple<Float, Float, Float>) = IntArray(c.size) { hsl(c[it].first, c[it].second, c[it].third) }
private fun t(h: Float, s: Float = 1f, l: Float = 0.55f) = Triple(h, s, l)

object Themes {
    val DEFAULT = Look()
    private val map = mapOf(
        "club" to Look(mv = 0.55f, bk = 0.5f, beat = 1.6f, gap = 120, thr = 0.04f, strobe = true,
            palette = pal(t(186f), t(312f), t(228f), t(95f))),                      // кислотный клуб: бирюза, маджента, электрик, лайм
        "street" to Look(mv = 1.4f, bk = 1.5f, beat = 1.3f, gap = 260, fat = 1.8f,
            palette = pal(t(45f, 1f, 0.52f), t(280f), t(0f, 1f, 0.5f), t(200f))),         // золото, фиолет, красный, синий
        "rock" to Look(mv = 0.8f, bk = 0.7f, beat = 1.2f, shake = true, overlay = 0xFFFF4A00.toInt(),
            palette = pal(t(5f, 1f, 0.5f), t(24f), t(350f, 1f, 0.45f), t(40f))),          // огонь: красный, оранжевый, кровь, янтарь
        "pop" to Look(mv = 0.8f, bk = 0.7f, beat = 1.3f,
            palette = pal(t(330f, 1f, 0.62f), t(195f, 1f, 0.6f), t(55f, 1f, 0.6f), t(285f, 1f, 0.65f))),   // конфеты
        "night" to Look(mv = 2f, bk = 2.2f, beat = 0.5f, gap = 320,
            palette = pal(t(275f, 1f, 0.5f), t(315f, 1f, 0.5f), t(235f, 1f, 0.5f), t(345f, 1f, 0.55f))),   // ночь: фиолет, маджента, синий, роза
        "calm" to Look(mv = 3f, bk = 3.5f, beat = 0f, fat = 0.7f,
            palette = pal(t(175f, 0.7f, 0.5f), t(210f, 0.7f, 0.55f), t(260f, 0.6f, 0.6f), t(150f, 0.6f, 0.5f))),   // море, небо, лаванда
        "warm" to Look(mv = 2f, bk = 2.2f, beat = 0.6f, gap = 320, overlay = 0xFFFFB347.toInt(), overlayTop = true,
            palette = pal(t(35f), t(22f), t(48f), t(12f, 0.9f, 0.5f))),                // костёр: янтарь, апельсин, золото, терракота
        "dark" to Look(mv = 2.2f, bk = 2.5f, beat = 1.3f, gap = 300, shake = true, fat = 1.4f, overlay = 0xFF8B0000.toInt(),
            palette = pal(t(0f, 1f, 0.36f), t(345f, 1f, 0.3f), t(275f, 0.9f, 0.3f), t(14f, 1f, 0.42f))),   // метал/дум: кровь, бордо, тёмный фиолет, тлеющий уголь
    )
    fun of(id: String?): Look = map[id] ?: DEFAULT
}
