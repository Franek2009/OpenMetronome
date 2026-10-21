package org.openmetronome.data

import org.openmetronome.domain.model.*

/** Versioned UTF-8 key/value payload. No Android types or locale-sensitive numbers. */
object ConfigCodec {
    fun encode(config: MetronomeConfig): String = with(config) {
        linkedMapOf(
            "version" to 1, "bpm" to tempo.bpm, "meter" to timeSignature.name, "accent" to accentFirstBeat,
            "probability" to silence.probability, "cycle" to silence.cycleEnabled,
            "audibleBars" to silence.audibleBars, "silentBars" to silence.silentBars,
            "visual" to visualPulse, "vibration" to vibration,
            "progression" to progression.enabled, "increment" to progression.increment, "interval" to progression.intervalSeconds,
            "ramp" to ramp.enabled, "start" to ramp.startBpm, "target" to ramp.targetBpm,
            "step" to ramp.step, "barsPerStep" to ramp.barsPerStep,
            "subdivision" to rhythm.subdivision.name, "accents" to rhythm.accents.joinToString(",") { it.name },
            "volumePercent" to sound.volumePercent, "clickSound" to sound.clickSound.name,
            "customSampleId" to (sound.customSampleId ?: ""),
            "beatAccents" to beatAccents.joinToString(",") { it.name },
        ).entries.joinToString("\n") { "${it.key}=${it.value}" }
    }
    fun decode(payload: String): MetronomeConfig {
        require(payload.length <= 16384) { "Configuration too large" }
        val values = payload.lineSequence().filter { it.isNotBlank() }.associate {
            val pair = it.split('=', limit = 2)
            require(pair.size == 2)
            pair[0] to pair[1]
        }
        require(values["version"] == "1") { "Unsupported configuration version" }
        fun number(key: String, default: Int) = values[key]?.toInt() ?: default
        fun flag(key: String, default: Boolean) = values[key]?.toBooleanStrict() ?: default
        return MetronomeConfig(
            tempo = Tempo(number("bpm", 120)),
            timeSignature = values["meter"]?.let(TimeSignature::valueOf) ?: TimeSignature.FOUR_FOUR,
            accentFirstBeat = flag("accent", true),
            silence = SilenceConfig(number("probability", 0), flag("cycle", false), number("audibleBars", 2), number("silentBars", 2)),
            visualPulse = flag("visual", true), vibration = flag("vibration", false),
            progression = SilenceProgression(flag("progression", false), number("increment", 10), number("interval", 60)),
            ramp = TempoRamp(flag("ramp", false), number("start", 60), number("target", 120), number("step", 5), number("barsPerStep", 4)),
            rhythm = RhythmConfig(values["subdivision"]?.let(Subdivision::valueOf) ?: Subdivision.SINGLE,
                values["accents"]?.takeIf { it.isNotEmpty() }?.split(',')?.map(ClickAccent::valueOf) ?: emptyList()),
            sound = SoundConfig(number("volumePercent", 100), values["clickSound"]?.let(ClickSound::valueOf) ?: ClickSound.CLASSIC,
                values["customSampleId"]?.takeIf { it.isNotEmpty() }),
            beatAccents = values["beatAccents"]?.takeIf { it.isNotEmpty() }?.split(',')?.map(ClickAccent::valueOf) ?: emptyList(),
        )
    }
}
