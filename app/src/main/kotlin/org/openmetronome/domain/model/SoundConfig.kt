package org.openmetronome.domain.model

// Names are persisted in settings/presets. Audition numbers stay stable across iterations.
enum class ClickSound(val auditionNumber: Int? = null) {
    CLASSIC, WOOD, SHORT, WOODBLOCK, RIMSHOT, COWBELL, CLAP, PULSE,
    ELECTRO_LOW(1), ELECTRO_MID(2), ELECTRO_HIGH(3), ELECTRO_DUAL(4),
    IMPULSE_SHORT(5), IMPULSE_FULL(6), IMPULSE_BRIGHT(7), IMPULSE_ROUGH(8),
    WOOD_DRY(9), CLAVES(10), WOOD_RESONANT(11), WOOD_HARD(12),
    METAL_BELL(13), METAL_ANVIL(14), METAL_PING(15), METAL_DRY(16),
    NOISE_CRACK(17), NOISE_BRUSH(18), NOISE_RIM(19), NOISE_WIDE(20),
    FULL_LOW_750(21), FULL_LOW_900(22), FULL_HIGH_1200(23), FULL_HIGH_1400(24), FULL_HIGH_1650(25),
    FULL_SHORT(26), FULL_TIGHT(27), FULL_LONG(28), FULL_HELD(29), FULL_SOFT(30),
    FULL_BRIGHT(31), FULL_ROUND(32), FULL_DENSE(33), FULL_LOW_DENSE(34), FULL_HIGH_TIGHT(35);

    companion object {
        val visible: List<ClickSound> = listOf(IMPULSE_FULL, FULL_LOW_750, FULL_HIGH_1200, FULL_HIGH_1650, FULL_SHORT, FULL_HELD)
        val standard: List<ClickSound> = entries.filter { it.auditionNumber == null }
        val experimental: List<ClickSound> = entries.filter { it.auditionNumber != null }
        val firstAudition: List<ClickSound> = experimental.filter { it.auditionNumber in 1..20 }
        val fullImpulseVariants: List<ClickSound> = experimental.filter { it.auditionNumber in 21..35 }
    }
}

data class SoundConfig(
    val volumePercent: Int = 100,
    val clickSound: ClickSound = ClickSound.IMPULSE_FULL,
    val customSampleId: String? = null,
) {
    init {
        require(volumePercent in 0..100)
        require(customSampleId == null || customSampleId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }
}
