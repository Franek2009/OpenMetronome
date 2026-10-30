package org.openmetronome

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import org.openmetronome.data.AppData
import androidx.test.platform.app.InstrumentationRegistry
import org.openmetronome.audio.AudioEngine
import org.openmetronome.domain.model.MetronomeConfig

/** Every instrumentation test runs against isolated storage, including Activity tests. */
class OpenMetronomeTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, TestApplication::class.java.name, context)
}
class TestApplication : OpenMetronomeApplication() {
    // Only instrumentation can install this memory-only engine; release has no such hook.
    internal var simulatedEngine: AudioEngine? = null
    override val data: AppData by lazy { AppData(this, isolatedTestStorage = true) }

    override fun createAudioEngine(context: Context): AudioEngine {
        simulatedEngine?.let { return it }
        val engine = super.createAudioEngine(context)
        return object : AudioEngine by engine {
            override fun start(config: MetronomeConfig) {
                // Defense in depth: even an unmarked test cannot acquire focus or play PCM.
                check(InstrumentationRegistry.getArguments().getString("allowAudio") == "true") {
                    "Hardware playback forbidden: explicit consent and allowAudio=true required"
                }
                engine.start(config)
            }
        }
    }

    override fun vibratePulse() {
        check(InstrumentationRegistry.getArguments().getString("allowHaptics") == "true") {
            "Hardware vibration forbidden: explicit consent and allowHaptics=true required"
        }
        super.vibratePulse()
    }
}
