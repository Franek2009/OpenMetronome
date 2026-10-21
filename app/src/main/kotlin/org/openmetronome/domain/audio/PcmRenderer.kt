package org.openmetronome.domain.audio

import kotlin.random.Random
import kotlin.math.roundToInt
import org.openmetronome.domain.model.ClickSound
import org.openmetronome.domain.model.ClickAccent
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.RhythmConfig

/** Continuous PCM. Decisions are per main pulse, never per buffer or subdivision. */
class PcmRenderer(
    sampleRate: Int, config: MetronomeConfig, random: Random = Random(System.nanoTime()),
    customSamples: Map<String, ShortArray> = emptyMap(),
    private val onPulse: (BeatEvent, Boolean) -> Unit = { _, _ -> },
) {
    private val training = TrainingController(sampleRate)
    private val scheduler = BeatScheduler(sampleRate, config, training::configAtBar)
    private val clicks = ClickSound.entries.associateWith { sound ->
        ClickGenerator.generate(sampleRate, false, sound) to ClickGenerator.generate(sampleRate, true, sound)
    }
    private val customClicks = customSamples.mapValues { (_, pcm) ->
        SamplePcm.click(pcm, sampleRate, false) to SamplePcm.click(pcm, sampleRate, true)
    }
    private fun bank(config: MetronomeConfig): Pair<ShortArray, ShortArray> =
        config.sound.customSampleId?.let { id -> customClicks[id] ?: throw MissingSampleException() }
            ?: clicks.getValue(config.sound.clickSound)
    private val volume = VolumeEnvelope(sampleRate, config.sound.volumePercent)
    private val silence = SilencePlanner(random)
    private var click = bank(config).first
    private var clickOffset = click.size
    private var slots = LongArray(0)
    private var slot = 0
    private var pulseSilent = false
    private var pulseAccent = false
    private var rhythm = RhythmConfig()
    var renderedFrames: Long = 0
        private set
    val activeConfig: MetronomeConfig get() = withCurrentVolume(scheduler.activeConfig)
    val appliedConfig: MetronomeConfig get() = withCurrentVolume(scheduler.appliedConfig)

    private fun withCurrentVolume(config: MetronomeConfig): MetronomeConfig =
        if (config.sound.volumePercent == volume.targetPercent) config
        else config.copy(sound = config.sound.copy(volumePercent = volume.targetPercent))

    fun render(target: ShortArray, count: Int = target.size, requested: MetronomeConfig) {
        require(count in 0..target.size)
        scheduler.updateConfig(requested)
        volume.target(requested.sound.volumePercent)
        for (index in 0 until count) {
            if (renderedFrames == scheduler.nextFrame) {
                val event = scheduler.nextBeat()
                rhythm = activeConfig.rhythm
                val trainingSilent = silence.isSilent(event.barIndex - training.startBar, activeConfig.silence)
                pulseSilent = trainingSilent || activeConfig.beatAccentAt(event.beatInBar) == ClickAccent.MUTE
                pulseAccent = event.accented
                slots = rhythm.subdivision.offsets.map { (n, d) -> scheduler.subdivisionFrame(n, d) }.toLongArray()
                slot = 0
                onPulse(event, pulseSilent || rhythm.accents.let { it.isNotEmpty() && it.all { accent -> accent == ClickAccent.MUTE } })
            }
            if (slot < slots.size && renderedFrames == slots[slot]) {
                val accent = rhythm.accentAt(slot)
                val bank = bank(scheduler.activeConfig)
                click = if (accent == ClickAccent.ACCENT || (slot == 0 && pulseAccent)) bank.second else bank.first
                clickOffset = if (pulseSilent || accent == ClickAccent.MUTE) click.size else 0
                slot++
            }
            val sample = if (clickOffset < click.size) click[clickOffset++].toInt() else 0
            val gain = volume.nextGain()
            target[index] = if (gain == 1.0) sample.toShort() else (sample * gain).roundToInt().toShort()
            renderedFrames++
        }
    }
}

class MissingSampleException : IllegalStateException("Brak wybranego sampla. Wybierz dostępne brzmienie.")
