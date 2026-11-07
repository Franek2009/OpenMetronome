package org.openmetronome.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import org.openmetronome.audio.PlaybackStatus
import org.junit.Rule
import org.junit.rules.RuleChain
import org.openmetronome.AudioPermissionRule
import org.openmetronome.UnlockedScreenRule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.openmetronome.MainActivity
import org.openmetronome.R
import org.openmetronome.domain.model.*

@RunWith(AndroidJUnit4::class)
class MetronomeScreenTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(AudioPermissionRule())
        .around(UnlockedScreenRule()).around(compose)

    @Before
    fun resetIsolatedSettings() {
        val model = ViewModelProvider(compose.activity)[MetronomeViewModel::class.java]
        model.stop()
        compose.waitUntil(10000) {
            model.state.value.audio.status == PlaybackStatus.STOPPED ||
                model.state.value.audio.status == PlaybackStatus.ERROR
        }
        model.setConfig(org.openmetronome.domain.model.MetronomeConfig())
        compose.waitForIdle()
    }

    @After
    fun stopAudioEvenWhenAnAssertionFails() {
        val model = ViewModelProvider(compose.activity)[MetronomeViewModel::class.java]
        model.stop()
        compose.waitUntil(10000) {
            model.state.value.audio.status == PlaybackStatus.STOPPED ||
                model.state.value.audio.status == PlaybackStatus.ERROR
        }
    }

    @Test
    fun initialScreenShowsDefaultsAndReadyAudio() {
        compose.onNodeWithText(text(R.string.app_name)).assertDoesNotExist()
        compose.onNodeWithText("Metronom").assertIsDisplayed()
        compose.onNodeWithContentDescription("Presety").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.tempo_value, 120))
            .assertIsDisplayed()
        compose.onNodeWithText("4/4").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Puls 1: akcent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.start)).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(text(R.string.foreground_notice))
            .assertIsDisplayed()
    }

    @Test
    fun buttonsAndSliderUpdateTempoAndRespectBoundaries() {
        compose.onNodeWithContentDescription(text(R.string.increase_tempo)).performClick()
        compose.onNodeWithContentDescription(text(R.string.tempo_value, 121))
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.decrease_tempo)).performClick()
        compose.onNodeWithContentDescription(text(R.string.tempo_value, 120))
            .assertIsDisplayed()

        setSlider(157.6f)
        compose.onNodeWithContentDescription(text(R.string.tempo_value, 158))
            .assertIsDisplayed()
        setSlider(20f)
        compose.onNodeWithContentDescription(text(R.string.decrease_tempo))
            .assertIsNotEnabled()
        setSlider(300f)
        compose.onNodeWithContentDescription(text(R.string.increase_tempo))
            .assertIsNotEnabled()
    }

    @Test
    fun settingsSurviveActivityRecreation() {
        setSlider(87f)
        compose.onNodeWithText("6/8").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Puls 1: akcent").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Puls 1: cisza").performClick()

        compose.activityRule.scenario.recreate()

        compose.onNodeWithContentDescription(text(R.string.tempo_value, 87))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("6/8").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Puls 1: zwykły").performScrollTo().assertIsDisplayed()
    }

    private fun setSlider(value: Float) {
        compose.onNodeWithContentDescription(text(R.string.tempo_slider))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(value)
            }
    }

    private fun text(resource: Int, vararg arguments: Any): String =
        compose.activity.getString(resource, *arguments)
}
