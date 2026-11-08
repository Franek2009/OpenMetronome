package org.openmetronome.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.openmetronome.AudioPermissionRule
import org.openmetronome.UnlockedScreenRule
import org.openmetronome.audio.*
import org.openmetronome.domain.model.*
import org.openmetronome.ui.theme.OpenMetronomeTheme

/** Presentation only: no service, focus, audio sink or user storage. */
class PlaybackCopyTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(AudioPermissionRule()).around(UnlockedScreenRule()).around(compose)

    @Test fun removingIdleExplanationPreservesLiveStatusErrorsAndActions() {
        val state = mutableStateOf(MetronomeState(MetronomeConfig()))
        var starts = 0; var stops = 0
        compose.setContent { OpenMetronomeTheme { PlaybackControls(state.value, { starts++ }, { stops++ }) } }
        compose.onNodeWithText("Start").assertIsEnabled().performClick()
        compose.runOnIdle { state.value = state.value.copy(audio = AudioEngineState(PlaybackStatus.PLAYING, state.value.config)) }
        compose.onNodeWithText("Metronom gra").assertIsDisplayed()
        compose.onNodeWithText("Stop").assertIsEnabled().performClick()
        compose.runOnIdle { state.value = state.value.copy(config = state.value.config.copy(tempo = Tempo(121))) }
        compose.onNodeWithText("Zmiana od następnego taktu").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(audio = AudioEngineState(PlaybackStatus.STOPPING)) }
        compose.onNodeWithText("Zatrzymywanie…").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(audio = AudioEngineState(PlaybackStatus.ERROR, failure = AudioFailure.FOCUS_DENIED)) }
        compose.onNodeWithText("Nie można teraz uzyskać dostępu do dźwięku. Spróbuj ponownie.").assertIsDisplayed()
        compose.onNodeWithText("Spróbuj ponownie").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(audio = AudioEngineState()) }
        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.onNodeWithText("Metronom gra").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(1, stops) }
    }

    @Test fun startAndPercentagesRemainVisibleWithLargeTextOnSmallScreen() {
        compose.setContent { OpenMetronomeTheme {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                Box(Modifier.requiredSize(320.dp, 520.dp)) {
                    MetronomeScreen(MetronomeState(MetronomeConfig()), {}, {}, {}, {}, {}, {}, onConfigChange = {})
                }
            }
        } }
        compose.onNodeWithText("Start").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("75%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Puls 1: akcent").assertExists()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                java.io.File(instrumentation.targetContext.cacheDir, "ui-copy-large-font.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { bitmap.recycle() }
        }
    }
}
