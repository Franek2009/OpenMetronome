package org.openmetronome

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.openmetronome.domain.model.MetronomeConfig

class HardwareSafetyTest {
    @Test fun unmarkedTestCannotStartHardwareWithoutAudioOptIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowAudio") != "true")
        val app = ApplicationProvider.getApplicationContext<TestApplication>()
        val engine = app.createAudioEngine(app)
        try {
            assertThrows(IllegalStateException::class.java) { engine.start(MetronomeConfig()) }
        } finally { engine.close() }
    }

    @Test fun vibrationCannotReachHardwareWithoutSeparateOptIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowHaptics") != "true")
        val app = ApplicationProvider.getApplicationContext<TestApplication>()
        assertThrows(IllegalStateException::class.java) { app.vibratePulse() }
    }
}
