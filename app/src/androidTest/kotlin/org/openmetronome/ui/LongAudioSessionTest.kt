package org.openmetronome.ui

import android.os.SystemClock
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.openmetronome.MainActivity
import org.openmetronome.audio.PlaybackStatus
import org.openmetronome.AudioPermissionRule
import org.openmetronome.UnlockedScreenRule
import org.openmetronome.RequiresAudio
import androidx.lifecycle.Lifecycle

/** Real-time hardware acceptance, deliberately without Compose's virtual test clock. */
@RunWith(AndroidJUnit4::class)
@RequiresAudio
class LongAudioSessionTest {
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(AudioPermissionRule()).around(UnlockedScreenRule())

    @Test fun thirtyMinutesWithoutUnderruns() = runSession(screenOff = false)

    @Test fun thirtyMinutesWithScreenOffWithoutUnderruns() = runSession(screenOff = true)

    private fun runSession(screenOff: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("longAudio") == "true")
        val report = java.io.File(instrumentation.targetContext.cacheDir,
            if (screenOff) "audio-screen-off-session.txt" else "m1-long-session.txt")
        report.writeText("START: real-time hardware acceptance; screenOff=$screenOff\n")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: MetronomeViewModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[MetronomeViewModel::class.java]
                model.setConfig(org.openmetronome.domain.model.MetronomeConfig(tempo = org.openmetronome.domain.model.Tempo(137)))
            }
            try {
                scenario.onActivity { model.start() }
                await {
                    model.state.value.audio.status == PlaybackStatus.PLAYING ||
                        model.state.value.audio.status == PlaybackStatus.ERROR
                }
                assertEquals(PlaybackStatus.PLAYING, model.state.value.audio.status)
                // The real UI must apply KEEP_SCREEN_ON before starting the wall-clock run.
                await {
                    var keepsScreenOn = false
                    scenario.onActivity { activity ->
                        keepsScreenOn = activity.window.attributes.flags and
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
                    }
                    keepsScreenOn
                }
                val power = instrumentation.targetContext.getSystemService(PowerManager::class.java)
                if (screenOff) {
                    scenario.moveToState(Lifecycle.State.CREATED)
                    shellKey("KEYCODE_SLEEP")
                    await { !power.isInteractive }
                }
                val started = SystemClock.elapsedRealtime()
                report.appendText("MONITORING: screenOff=$screenOff\n")
                java.io.FileOutputStream(report, true).bufferedWriter().use { out ->
                    var lastMinute = -1L
                    var lastPlayed = -1L
                    while (SystemClock.elapsedRealtime() - started < 30 * 60 * 1000L) {
                        val state = model.state.value.audio
                        assertEquals(PlaybackStatus.PLAYING, state.status)
                        val diagnostics = checkNotNull(state.diagnostics)
                        assertEquals(0, diagnostics.underruns)
                        if (screenOff) assertTrue("Screen unexpectedly woke", !power.isInteractive)
                        val minute = (SystemClock.elapsedRealtime() - started) / 60000
                        if (minute != lastMinute) {
                            assertTrue("Playback head stopped advancing", diagnostics.framesPlayed > lastPlayed)
                            lastPlayed = diagnostics.framesPlayed
                            val message = "minute=$minute $diagnostics"
                            out.appendLine(message)
                            out.flush()
                            Log.i("OpenMetronomeLongTest", message)
                            lastMinute = minute
                        }
                        // Only this test waits on wall time. Audio never uses this clock.
                        SystemClock.sleep(1000)
                    }
                    val finalState = model.state.value.audio
                    assertEquals(PlaybackStatus.PLAYING, finalState.status)
                    assertEquals(0, checkNotNull(finalState.diagnostics).underruns)
                    out.appendLine("PASS: 30 minutes, ${finalState.diagnostics}")
                }
            } catch (error: Throwable) {
                report.appendText("FAIL: ${error.javaClass.simpleName}: ${error.message}\n")
                Log.e("OpenMetronomeLongTest", "hardware acceptance failed", error)
                throw error
            } finally {
                try {
                    model.stop()
                    await { model.state.value.audio.status == PlaybackStatus.STOPPED }
                } finally {
                    if (screenOff) shellKey("KEYCODE_WAKEUP")
                }
            }
        }
    }

    private fun shellKey(key: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("input keyevent $key"),
        ).use { it.readBytes() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("Hardware test condition timed out", condition())
    }
}
