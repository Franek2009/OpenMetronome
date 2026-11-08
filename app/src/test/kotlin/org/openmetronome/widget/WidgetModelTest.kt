package org.openmetronome.widget

import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.audio.*
import org.openmetronome.domain.model.*

class WidgetModelTest {
    @Test fun percentagesKeepBaseAndExactFractionalTempo() {
        val config = MetronomeConfig(tempo = Tempo(123))
        val slow = WidgetModel.edit(config, "25")
        assertEquals(123, slow.tempo.bpm)
        assertEquals("30.75", WidgetModel.playback(MetronomeState(slow)).bpm)
        assertEquals(config, WidgetModel.edit(slow, "100"))
    }
    @Test fun smallestEffectiveTempoAndClampedBase() {
        val low = MetronomeConfig(tempo = Tempo(20), tempoPercent = TempoPercent.QUARTER)
        assertEquals(low, WidgetModel.edit(low, "minus"))
        assertEquals("5", WidgetModel.playback(MetronomeState(low)).bpm)
        val high = low.copy(tempo = Tempo(300))
        assertEquals(high, WidgetModel.edit(high, "plus"))
        assertEquals(299, WidgetModel.edit(high, "minus").tempo.bpm)
    }
    @Test fun pendingEditsNeverPretendNewTempoIsAlreadyPlaying() {
        val active = MetronomeConfig(tempo = Tempo(120))
        val selected = active.copy(tempoPercent = TempoPercent.HALF)
        val view = WidgetModel.playback(MetronomeState(selected, AudioEngineState(PlaybackStatus.PLAYING,
            activeConfig = active, appliedConfig = active)))
        assertEquals("120", view.bpm)
        assertEquals(50, view.percent)
        assertTrue(view.pending)
        assertTrue(view.running)
    }
    @Test fun individualPulseAndDiagnosticsDoNotChangeWidgetProjection() {
        val state = MetronomeState(audio = AudioEngineState(status = PlaybackStatus.PLAYING))
        val beat = state.copy(audio = state.audio.copy(pulse = PlaybackPulse(48000, 2, 1, false),
            diagnostics = AudioDiagnostics(48000, 256, 1024, 48000, 0)))
        assertEquals(WidgetModel.playback(state), WidgetModel.playback(beat))
    }
    @Test fun coldProcessDoesNotRestoreRunningFlagAndFailuresAreVisible() {
        assertFalse(WidgetModel.playback(MetronomeState()).running)
        val error = WidgetModel.playback(MetronomeState(audio = AudioEngineState(PlaybackStatus.ERROR)))
        assertFalse(error.running)
        assertTrue(error.status.contains("Błąd"))
        val stopping = WidgetModel.playback(MetronomeState(audio = AudioEngineState(PlaybackStatus.STOPPING)))
        assertEquals("Zatrzymywanie", stopping.status)
        assertFalse(stopping.running)
    }
    @Test fun manualStepsDisableRampLikeTheAppButPercentagesKeepIt() {
        val config = MetronomeConfig(ramp = TempoRamp(enabled = true))
        assertFalse(WidgetModel.edit(config, "plus").ramp.enabled)
        assertFalse(WidgetModel.edit(config, "minus").ramp.enabled)
        assertTrue(WidgetModel.edit(config, "75").ramp.enabled)
    }
    @Test fun unrelatedCommandsCannotRewriteConfiguration() {
        val config = MetronomeConfig()
        for (command in listOf("start", "stop", "", "200", "../")) assertEquals(config, WidgetModel.edit(config, command))
    }
}
