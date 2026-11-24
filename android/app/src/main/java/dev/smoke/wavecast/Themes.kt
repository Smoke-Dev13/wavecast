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
    /** Цвета глаза зверя: [радужка, свечение, тёмный ободок, глаза вдали]. null — фирменные (волк, янтарь). */
    val palette: IntArray? = null,
    /** Круглый зрачок (волк, сова, тигр) или вертикальная щель (кошка, хищник, лиса, рептилия). */
    val round: Boolean = false,
)


private fun eye(vararg hex: Long) = IntArray(hex.size) { hex[it].toInt() }

object Themes {
    /** Фирменные глаза, когда жанр не найден: янтарный волк. */
    val BRAND = eye(0xFFF5B700, 0xFFC77700, 0xFF8A3C00, 0xFF7FE3FF)
    val DEFAULT = Look(round = true)
    private val map = mapOf(
        "club" to Look(mv = 0.55f, bk = 0.5f, beat = 1.6f, gap = 120, thr = 0.04f, strobe = true,
            palette = eye(0xFF2EE88A, 0xFF119C5E, 0xFF0B5A3A, 0xFFE8FF7A)),                 // кошка: изумруд, золотой ободок
        "street" to Look(mv = 1.4f, bk = 1.5f, beat = 1.3f, gap = 260, fat = 1.8f,
            palette = eye(0xFFD8FF3A, 0xFF8AA800, 0xFF3A4A00, 0xFFFF7A1A)),                 // рептилия: кислотно-жёлтый, олива
        "rock" to Look(mv = 0.8f, bk = 0.7f, beat = 1.2f, shake = true, overlay = 0xFFFF4A00.toInt(), round = true,
            palette = eye(0xFFFFB000, 0xFFFF5A00, 0xFF7A2A00, 0xFFFFE27A)),                 // волк: золото, огонь
        "pop" to Look(mv = 0.8f, bk = 0.7f, beat = 1.3f,
            palette = eye(0xFFFF7A1A, 0xFFFF4F6E, 0xFF7A2400, 0xFF3FE6D8)),                 // лиса: рыжий, бирюза вдали
        "night" to Look(mv = 2f, bk = 2.2f, beat = 0.5f, gap = 320, round = true,
            palette = eye(0xFFFF9A1F, 0xFF7A3CFF, 0xFF6A2A00, 0xFFFFD37A)),                 // сова: медовый на фиолетовой ночи
        "calm" to Look(mv = 3f, bk = 3.5f, beat = 0f, fat = 0.7f, round = true,
            palette = eye(0xFFE8B45A, 0xFF3FA8C8, 0xFF5A3A10, 0xFF9FE8E0)),                 // олень: мёд, вода
        "warm" to Look(mv = 2f, bk = 2.2f, beat = 0.6f, gap = 320, overlay = 0xFFFFB347.toInt(), overlayTop = true, round = true,
            palette = eye(0xFFFF8A00, 0xFFFFB347, 0xFF7A2000, 0xFFFFE08A)),                 // тигр: огненный, карамель
        "dark" to Look(mv = 2.2f, bk = 2.5f, beat = 1.3f, gap = 300, shake = true, fat = 1.4f, overlay = 0xFF8B0000.toInt(),
            palette = eye(0xFFE0122E, 0xFF8A0018, 0xFF3A0008, 0xFFFF5A1F)),                 // хищник в темноте: кровь, бордо
    )
    fun of(id: String?): Look = map[id] ?: DEFAULT
}
