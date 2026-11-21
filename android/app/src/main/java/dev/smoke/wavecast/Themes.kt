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
)

object Themes {
    val DEFAULT = Look()
    private val map = mapOf(
        "club" to Look(mv = 0.55f, bk = 0.5f, beat = 1.6f, gap = 120, thr = 0.04f, strobe = true),
        "street" to Look(mv = 1.4f, bk = 1.5f, beat = 1.3f, gap = 260, fat = 1.8f),
        "rock" to Look(mv = 0.8f, bk = 0.7f, beat = 1.2f, shake = true, overlay = 0xFFFF4A00.toInt()),
        "pop" to Look(mv = 0.8f, bk = 0.7f, beat = 1.3f),
        "night" to Look(mv = 2f, bk = 2.2f, beat = 0.5f, gap = 320),
        "calm" to Look(mv = 3f, bk = 3.5f, beat = 0f, fat = 0.7f),
        "warm" to Look(mv = 2f, bk = 2.2f, beat = 0.6f, gap = 320, overlay = 0xFFFFB347.toInt(), overlayTop = true),
    )
    fun of(id: String?): Look = map[id] ?: DEFAULT
}
