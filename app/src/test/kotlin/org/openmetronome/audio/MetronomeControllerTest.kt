package org.openmetronome.audio

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.Tempo

class MetronomeControllerTest {
    @Test fun `effective training tempo and silence are not mistaken for pending user changes`() {
        val requested = MetronomeConfig(ramp = org.openmetronome.domain.model.TempoRamp(enabled = true),
            progression = org.openmetronome.domain.model.SilenceProgression(enabled = true))
        val effective = requested.copy(tempo = Tempo(75), silence = requested.silence.copy(probability = 30))
        val state = MetronomeState(requested, AudioEngineState(PlaybackStatus.PLAYING,
            activeConfig = effective, appliedConfig = requested))
        assertFalse(state.hasPendingChanges)
        assertTrue(state.copy(config = requested.copy(accentFirstBeat = false)).hasPendingChanges)
        assertFalse(state.copy(audio = state.audio.copy(status = PlaybackStatus.STOPPED)).hasPendingChanges)
    }

    private fun await(controller: MetronomeController, predicate: (MetronomeState) -> Boolean): MetronomeState =
        runBlocking { withTimeout(5000) { controller.state.first(predicate) } }

    @Test fun `requested and active config stay distinct until engine applies the change`() {
        val engine = FakeAudioEngine()
        MetronomeController(engine).use { controller ->
            controller.start()
            await(controller) { it.audio.status == PlaybackStatus.PLAYING }
            controller.editConfig { it.copy(tempo = Tempo(137)) }
            assertEquals(137, controller.state.value.config.tempo.bpm)
            assertEquals(120, controller.state.value.audio.activeConfig?.tempo?.bpm)
            assertTrue(controller.state.value.hasPendingChanges)
            engine.state.value = engine.state.value.copy(activeConfig = controller.state.value.config)
            await(controller) { !it.hasPendingChanges }
            controller.stop()
            assertFalse(await(controller) { it.audio.status == PlaybackStatus.STOPPED }.keepsScreenOn)
        }
        assertTrue(engine.closed)
    }

    @Test fun `audio state updates do not overwrite settings and errors allow retry`() {
        val engine = FakeAudioEngine()
        MetronomeController(engine).use { controller ->
            controller.editConfig { it.copy(tempo = Tempo(20), accentFirstBeat = false) }
            engine.state.value = AudioEngineState(PlaybackStatus.ERROR, failure = AudioFailure.FOCUS_DENIED)
            val state = await(controller) { it.audio.status == PlaybackStatus.ERROR }
            assertEquals(20, state.config.tempo.bpm)
            assertFalse(state.keepsScreenOn)
            controller.start()
            assertEquals(Tempo(20), await(controller) { it.keepsScreenOn }.audio.activeConfig?.tempo)
        }
    }

    @Test fun `closed controller ignores commands`() {
        val engine = FakeAudioEngine()
        val controller = MetronomeController(engine)
        controller.close()
        controller.editConfig { it.copy(tempo = Tempo(80)) }
        controller.start()
        assertEquals(MetronomeConfig(), controller.state.value.config)
        assertEquals(0, engine.starts)
    }

    @Test fun `platform initialization error is visible until explicit retry`() {
        val engine = FakeAudioEngine()
        MetronomeController(engine).use { controller ->
            controller.reportFailure(AudioFailure.INITIALIZATION)
            assertEquals(AudioFailure.INITIALIZATION, controller.state.value.audio.failure)
            controller.start()
            assertEquals(PlaybackStatus.PLAYING, controller.state.value.audio.status)
            assertNull(controller.state.value.audio.failure)
        }
    }
}
