package org.openmetronome.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.openmetronome.AudioPermissionRule
import org.openmetronome.UnlockedScreenRule
import org.openmetronome.audio.*
import org.openmetronome.ui.theme.OpenMetronomeTheme

/** UI simulation only. These callbacks never reach a controller or hardware engine. */
class SimulatedControlsTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(AudioPermissionRule()).around(UnlockedScreenRule()).around(compose)

    @Test fun simulatedStartAndStopRemainVisibleWhenSettingsAreScrolled() {
        val state = mutableStateOf(MetronomeState())
        var starts = 0
        var stops = 0
        compose.setContent {
            OpenMetronomeTheme {
                MetronomeScreen(state.value, {}, {}, {}, {},
                    onStart = { starts++; state.value = state.value.copy(audio = AudioEngineState(PlaybackStatus.PLAYING)) },
                    onStop = { stops++; state.value = state.value.copy(audio = AudioEngineState()) })
            }
        }
        compose.onNodeWithText("Start").assertIsDisplayed().performClick()
        compose.onNodeWithTag("training-toggle").performScrollTo()
        compose.onNodeWithText("Stop").assertIsDisplayed().performClick()
        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(1, stops) }
    }

    @Test fun copyingReportRequiresClickAndUsesInjectedCallbackNotSystemClipboard() {
        var copied: String? = null
        compose.setContent {
            OpenMetronomeTheme { DiagnosticsDialog("Raport testowy", {}, { copied = it }) }
        }
        compose.runOnIdle { assertNull(copied) }
        compose.onNodeWithText("Kopiuj raport").performClick()
        compose.runOnIdle { assertEquals("Raport testowy", copied) }
    }

    @Test fun narrowLayoutWithLargeTextKeepsControlsReachableWithoutHardware() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                OpenMetronomeTheme {
                    Box(Modifier.requiredWidth(360.dp).fillMaxHeight()) {
                        MetronomeScreen(MetronomeState(), {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.onNodeWithContentDescription("Puls 1: akcent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Zwiększ tempo o 5 BPM").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Trening pulsu").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Prawdopodobieństwo wyciszenia").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Start").assertIsDisplayed()
    }

    @Test fun navigationWithoutTitleWrapsAtLargeTextAndKeepsEveryAction() {
        var selected = "Metronom"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                OpenMetronomeTheme {
                    Box(Modifier.requiredWidth(360.dp)) { AppNavigation(selected) { selected = it } }
                }
            }
        }
        compose.onNodeWithText("OpenMetronome").assertDoesNotExist()
        for (page in listOf("Metronom", "Statystyki", "Ustawienia")) {
            compose.onNodeWithText(page).assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(page, selected) }
        }
        compose.onNodeWithContentDescription("Presety").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("Presety", selected) }
    }
}
