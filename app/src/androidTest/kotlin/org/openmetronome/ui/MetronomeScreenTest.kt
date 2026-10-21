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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import android.view.WindowManager
import org.openmetronome.audio.PlaybackStatus
import org.junit.Rule
import org.junit.rules.RuleChain
import org.openmetronome.AudioPermissionRule
import org.openmetronome.UnlockedScreenRule
import org.openmetronome.RequiresAudio
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

    @Test
    @RequiresAudio
    fun playbackSurvivesRecreationAndLeavingForeground() {
        val model = ViewModelProvider(compose.activity)[MetronomeViewModel::class.java]
        compose.onNodeWithText(text(R.string.start)).performClick()
        compose.waitUntil(10000) { model.state.value.audio.status == PlaybackStatus.PLAYING }
        compose.onNodeWithText(text(R.string.stop)).assertIsEnabled()
        compose.runOnIdle {
            org.junit.Assert.assertTrue(
                compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0,
            )
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.stop)).assertIsDisplayed().assertIsEnabled()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        org.junit.Assert.assertEquals(PlaybackStatus.PLAYING, model.state.value.audio.status)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText(text(R.string.stop)).performClick()
        compose.waitUntil(10000) { model.state.value.audio.status == PlaybackStatus.STOPPED }
        compose.onNodeWithText(text(R.string.start)).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    @RequiresAudio
    fun repeatedStartStopDoesNotLeaveAudioPlaying() {
        val model = ViewModelProvider(compose.activity)[MetronomeViewModel::class.java]
        repeat(5) {
            compose.onNodeWithText(text(R.string.start)).performClick()
            compose.waitUntil(10000) { model.state.value.audio.status == PlaybackStatus.PLAYING }
            compose.onNodeWithText(text(R.string.stop)).performClick()
            compose.waitUntil(10000) { model.state.value.audio.status == PlaybackStatus.STOPPED }
        }
        compose.runOnIdle {
            org.junit.Assert.assertEquals(
                0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    @Test
    @RequiresAudio
    fun shortTrainingAndSoundChangesReachAudioRenderer() {
        val model = ViewModelProvider(compose.activity)[MetronomeViewModel::class.java]
        val initial = MetronomeConfig(tempo = Tempo(300), sound = SoundConfig(50, ClickSound.WOOD),
            rhythm = RhythmConfig(Subdivision.EIGHTHS), silence = SilenceConfig(50, true, 1, 1),
            beatAccents = listOf(ClickAccent.ACCENT, ClickAccent.MUTE, ClickAccent.NORMAL, ClickAccent.NORMAL))
        compose.runOnIdle { model.setConfig(initial) }
        compose.onNodeWithText(text(R.string.start)).performClick()
        compose.waitUntil(10000) { model.state.value.audio.status == PlaybackStatus.PLAYING }
        compose.waitUntil(10000) {
            model.state.value.audio.diagnostics?.let { it.framesPlayed >= it.sampleRate * 2L } == true
        }
        val next = initial.withTimeSignature(TimeSignature.SIX_EIGHT).copy(tempo = Tempo(137),
            sound = SoundConfig(50, ClickSound.SHORT), silence = SilenceConfig(0))
        compose.runOnIdle { model.setConfig(next) }
        compose.waitUntil(10000) { model.state.value.audio.appliedConfig == next }
        val before = model.state.value.audio.diagnostics!!.framesPlayed
        val halfSecond = model.state.value.audio.diagnostics!!.sampleRate / 2
        compose.waitUntil(10000) { (model.state.value.audio.diagnostics?.framesPlayed ?: 0) > before + halfSecond }
        val silent = next.copy(tempo = Tempo(300), silence = SilenceConfig(100))
        compose.runOnIdle { model.setConfig(silent) }
        compose.waitUntil(10000) {
            model.state.value.audio.appliedConfig == silent && model.state.value.audio.pulse?.silent == true
        }
        val restored = silent.copy(silence = SilenceConfig(0), sound = SoundConfig(50, ClickSound.CLASSIC))
        compose.runOnIdle { model.setConfig(restored) }
        compose.waitUntil(10000) { model.state.value.audio.appliedConfig == restored }
        compose.runOnIdle {
            org.junit.Assert.assertEquals(PlaybackStatus.PLAYING, model.state.value.audio.status)
            org.junit.Assert.assertEquals(0, model.state.value.audio.diagnostics!!.underruns)
        }
        compose.onNodeWithText(text(R.string.stop)).performClick()
        compose.waitUntil(10000) { model.state.value.audio.status == PlaybackStatus.STOPPED }
    }

    @Test
    @RequiresAudio
    fun importedSampleAndCuratedSoundsPlayAndReleaseLibrary() {
        val app = compose.activity.application as org.openmetronome.TestApplication
        val data = app.data
        val model = ViewModelProvider(compose.activity)[MetronomeViewModel::class.java]
        val id = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            data.ready.await()
            val bytes = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .context.assets.open("samples/tone.mp3").use { it.readBytes() }
            data.samples.importSample("Audio test fixture") {
                org.openmetronome.platform.audio.AndroidSampleDecoder(compose.activity).decodeBytes(bytes)
            }.first
        }
        try {
            val config = MetronomeConfig(tempo = Tempo(300), timeSignature = TimeSignature.TWO_FOUR,
                beatAccents = listOf(ClickAccent.ACCENT, ClickAccent.NORMAL),
                sound = SoundConfig(50, ClickSound.IMPULSE_FULL, id))
            compose.runOnIdle { model.setConfig(config) }
            compose.onNodeWithText(text(R.string.start)).performClick()
            compose.waitUntil(10000) {
                model.state.value.audio.let {
                    it.status == PlaybackStatus.PLAYING && it.appliedConfig == config &&
                        (it.diagnostics?.framesPlayed ?: 0) >= (it.diagnostics?.sampleRate ?: 48000) * 2L
                }
            }
            for (sound in ClickSound.visible) {
                val next = config.copy(sound = SoundConfig(50, sound))
                compose.runOnIdle { model.setConfig(next) }
                compose.waitUntil(10000) { model.state.value.audio.appliedConfig == next }
                val before = model.state.value.audio.diagnostics!!.framesPlayed
                val rate = model.state.value.audio.diagnostics!!.sampleRate
                compose.waitUntil(10000) { (model.state.value.audio.diagnostics?.framesPlayed ?: 0) >= before + rate / 2 }
            }
            compose.runOnIdle { org.junit.Assert.assertEquals(0, model.state.value.audio.diagnostics!!.underruns) }
        } finally {
            model.stop()
            compose.waitUntil(10000) {
                model.state.value.audio.status == PlaybackStatus.STOPPED || model.state.value.audio.status == PlaybackStatus.ERROR
            }
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { data.samples.delete(id) }
            compose.runOnIdle { model.setConfig(MetronomeConfig()) }
        }
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
