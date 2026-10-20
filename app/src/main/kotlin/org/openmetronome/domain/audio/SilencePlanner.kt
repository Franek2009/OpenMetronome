package org.openmetronome.domain.audio

import kotlin.random.Random
import org.openmetronome.domain.model.SilenceConfig

/** Called exactly once per main pulse, including pulses in silent bars. */
class SilencePlanner(private val random: Random) {
    fun isSilent(bar: Long, config: SilenceConfig): Boolean {
        val randomSilent = random.nextInt(100) < config.probability
        val cycleSilent = config.cycleEnabled &&
            bar % (config.audibleBars + config.silentBars) >= config.audibleBars
        return randomSilent || cycleSilent
    }
}
