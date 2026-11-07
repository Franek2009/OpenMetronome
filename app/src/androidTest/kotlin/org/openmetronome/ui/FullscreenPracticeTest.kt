package org.openmetronome.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.openmetronome.AudioPermissionRule
import org.openmetronome.UnlockedScreenRule
import org.openmetronome.audio.*
import org.openmetronome.domain.model.*
import org.openmetronome.ui.theme.OpenMetronomeTheme

class FullscreenPracticeTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(AudioPermissionRule()).around(UnlockedScreenRule()).around(compose)

    @Test fun lockingKeepsStopAvailableAndDoesNotRestartTheSession() {
        val config = MetronomeConfig(tempo = Tempo(121), tempoPercent = TempoPercent.THREE_QUARTERS)
        val state = mutableStateOf(MetronomeState(config, AudioEngineState(PlaybackStatus.PLAYING, config,
            pulse = PlaybackPulse(48000, 3, 1, false))))
        var starts = 0; var stops = 0; var exits = 0
        compose.setContent { OpenMetronomeTheme {
            FullscreenPractice(state.value, { starts++ }, { stops++; state.value = state.value.copy(audio = AudioEngineState()) }, { exits++ }, true, false)
        } }
        compose.onNodeWithContentDescription("Tempo pełnego ekranu: 90.75 BPM").assertIsDisplayed()
        compose.onNodeWithText("Puls 2 / 4 · takt 4").assertIsDisplayed()
        compose.onNodeWithText("Zablokuj dotyk").performClick()
        compose.onNodeWithText("Wróć").assertIsNotEnabled()
        compose.onNodeWithText("Stop").assertIsEnabled().performClick()
        compose.onNodeWithText("Start").assertIsNotEnabled()
        compose.onNodeWithText("Odblokuj").performClick()
        compose.onNodeWithText("Wróć").performClick()
        compose.runOnIdle { assertEquals(0, starts); assertEquals(1, stops); assertEquals(1, exits) }
    }

    @Test fun changingPresentationPreservesEngineIdentityAndPulse() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val data = org.openmetronome.data.AppData(context, isolatedTestStorage = true)
        kotlinx.coroutines.runBlocking { data.ready.await() }
        val config = MetronomeConfig()
        val snapshot = MetronomeState(config, AudioEngineState(PlaybackStatus.PLAYING, config,
            diagnostics = AudioDiagnostics(48000, 256, 1024, 96000, 0, 48000, 771),
            pulse = PlaybackPulse(48000, 8, 2, false)))
        var commands = 0
        val port = object : MetronomePort {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(snapshot)
            override fun start() { commands++ }
            override fun stop() { commands++ }
            override fun close() = Unit
            override fun editConfig(transform: (MetronomeConfig) -> MetronomeConfig) { state.value = state.value.copy(config = transform(state.value.config)) }
        }
        val model = MetronomeViewModel(port)
        try {
            compose.setContent { OpenMetronomeTheme { OpenMetronomeApp(model, data) } }
            compose.onNodeWithText("Pełny ekran").performScrollTo().performClick()
            compose.onNodeWithText("Puls 3 / 4 · takt 9").assertIsDisplayed()
            compose.onNodeWithText("Wróć").performClick()
            compose.onNodeWithText("Pełny ekran").assertIsDisplayed()
            compose.runOnIdle { assertEquals(snapshot, model.state.value); assertEquals(0, commands) }
        } finally {
            compose.setContent { }
            kotlinx.coroutines.runBlocking { data.closeTestStorage() }
        }
    }
}
