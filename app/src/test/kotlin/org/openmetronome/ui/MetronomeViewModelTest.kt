package org.openmetronome.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.After
import org.openmetronome.audio.FakeAudioEngine
import org.openmetronome.audio.MetronomeController
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.TimeSignature

class MetronomeViewModelTest {
    @Test fun `lamp edits compose atomically and meter resizing preserves mute`() {
        val model = model()
        model.cycleBeatAccent(1)
        model.cycleBeatAccent(1)
        assertEquals(org.openmetronome.domain.model.ClickAccent.MUTE, model.state.value.config.beatAccentAt(1))
        model.setTimeSignature(TimeSignature.SIX_EIGHT)
        assertEquals(6, model.state.value.config.beatAccents.size)
        assertEquals(org.openmetronome.domain.model.ClickAccent.MUTE, model.state.value.config.beatAccentAt(1))
        assertEquals(org.openmetronome.domain.model.ClickAccent.NORMAL, model.state.value.config.beatAccentAt(5))
    }
    @Test fun `five BPM adjustments clamp and disable ramp without starting audio`() {
        val engine = FakeAudioEngine()
        val controller = MetronomeController(engine)
        controllers.add(controller)
        val model = MetronomeViewModel(controller)
        model.setConfig(MetronomeConfig(ramp = org.openmetronome.domain.model.TempoRamp(enabled = true)))
        model.adjustTempo(5)
        assertEquals(125, model.state.value.config.tempo.bpm)
        assertFalse(model.state.value.config.ramp.enabled)
        model.setTempo(22); model.adjustTempo(-5)
        assertEquals(20, model.state.value.config.tempo.bpm)
        model.setTempo(298); model.adjustTempo(5)
        assertEquals(300, model.state.value.config.tempo.bpm)
        assertEquals(0, engine.starts)
    }
    @Test fun `tap edits tempo without playing and disables ramp only after a valid estimate`() {
        var now = 0L
        val engine = FakeAudioEngine()
        val controller = MetronomeController(engine)
        controllers.add(controller)
        val model = MetronomeViewModel(controller) { now }
        val config = MetronomeConfig(silence = org.openmetronome.domain.model.SilenceConfig(51),
            ramp = org.openmetronome.domain.model.TempoRamp(enabled = true),
            sound = org.openmetronome.domain.model.SoundConfig(37, org.openmetronome.domain.model.ClickSound.WOOD))
        model.setConfig(config)
        model.tapTempo()
        assertEquals(config, model.state.value.config)
        now = 1_000_000_000L; model.tapTempo()
        assertEquals(config.copy(tempo = org.openmetronome.domain.model.Tempo(60),
            ramp = config.ramp.copy(enabled = false)), model.state.value.config)
        assertEquals(0, engine.starts)
        model.resetTapTempo()
        now += 200_000_000L; model.tapTempo()
        assertEquals(60, model.state.value.config.tempo.bpm)
    }
    private val controllers = mutableListOf<MetronomeController>()
    private fun model(): MetronomeViewModel {
        val controller = MetronomeController(FakeAudioEngine())
        controllers.add(controller)
        return MetronomeViewModel(controller)
    }
    @After fun close() { controllers.forEach { it.close() } }
    @Test
    fun `initial state uses the domain defaults`() {
        assertEquals(MetronomeConfig(), model().state.value.config)
    }

    @Test
    fun `buttons change tempo by exactly one BPM`() {
        val viewModel = model()

        viewModel.increaseTempo()
        assertEquals(121, viewModel.state.value.config.tempo.bpm)
        viewModel.decreaseTempo()
        assertEquals(120, viewModel.state.value.config.tempo.bpm)
    }

    @Test
    fun `buttons stop at supported boundaries`() {
        val viewModel = model()

        viewModel.setTempo(20)
        repeat(3) { viewModel.decreaseTempo() }
        assertEquals(20, viewModel.state.value.config.tempo.bpm)
        viewModel.setTempo(300)
        repeat(3) { viewModel.increaseTempo() }
        assertEquals(300, viewModel.state.value.config.tempo.bpm)
    }

    @Test
    fun `input is clamped before constructing a tempo`() {
        val viewModel = model()

        viewModel.setTempo(Int.MIN_VALUE)
        assertEquals(20, viewModel.state.value.config.tempo.bpm)
        viewModel.setTempo(Int.MAX_VALUE)
        assertEquals(300, viewModel.state.value.config.tempo.bpm)
        viewModel.setTempo(157)
        assertEquals(157, viewModel.state.value.config.tempo.bpm)
    }

    @Test
    fun `changing meter and accent preserves selected tempo`() {
        val viewModel = model()
        viewModel.setTempo(87)

        viewModel.setTimeSignature(TimeSignature.SIX_EIGHT)
        viewModel.setAccentFirstBeat(false)

        assertEquals(87, viewModel.state.value.config.tempo.bpm)
        assertEquals(TimeSignature.SIX_EIGHT, viewModel.state.value.config.timeSignature)
        assertFalse(viewModel.state.value.config.accentFirstBeat)
    }

    @Test
    fun `new instance starts with defaults without persistent settings`() {
        val first = model()
        first.setTempo(80)

        assertEquals(MetronomeConfig(), model().state.value.config)
    }

    @Test fun `manual tempo commands disable automatic ramp without changing training silence`() {
        val viewModel = model()
        viewModel.setConfig(MetronomeConfig(
            ramp = org.openmetronome.domain.model.TempoRamp(enabled = true),
            silence = org.openmetronome.domain.model.SilenceConfig(50),
        ))
        viewModel.increaseTempo()
        assertFalse(viewModel.state.value.config.ramp.enabled)
        assertEquals(50, viewModel.state.value.config.silence.probability)
    }

    @Test fun `all manual tempo commands preserve the complete non-ramp program`() {
        val original = MetronomeConfig(tempo = org.openmetronome.domain.model.Tempo(137),
            timeSignature = TimeSignature.SIX_EIGHT, accentFirstBeat = false,
            silence = org.openmetronome.domain.model.SilenceConfig(51, true, 3, 4),
            visualPulse = false,
            progression = org.openmetronome.domain.model.SilenceProgression(true, 7, 13),
            ramp = org.openmetronome.domain.model.TempoRamp(true, 300, 20, 17, 3),
            rhythm = org.openmetronome.domain.model.RhythmConfig(org.openmetronome.domain.model.Subdivision.TRIPLETS))
        val viewModel = model()
        for ((command, bpm) in listOf<Pair<() -> Unit, Int>>(
            { viewModel.setTempo(211) } to 211,
            viewModel::increaseTempo to 138,
            viewModel::decreaseTempo to 136,
        )) {
            viewModel.setConfig(original)
            command()
            assertEquals(original.copy(tempo = org.openmetronome.domain.model.Tempo(bpm),
                ramp = original.ramp.copy(enabled = false)), viewModel.state.value.config)
        }
    }
}
