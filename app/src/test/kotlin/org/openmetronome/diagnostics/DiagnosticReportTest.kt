package org.openmetronome.diagnostics

import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.audio.*
import org.openmetronome.domain.model.*

class DiagnosticReportTest {
    private val device = DiagnosticDeviceInfo("0.6.1-dev", 5, "Test", "Phone", "15", 35)

    @Test fun `stopped report includes current settings and explains missing audio metrics`() {
        val config = MetronomeConfig(tempo = Tempo(87), sound = SoundConfig(37, ClickSound.WOOD))
        val report = DiagnosticReport.format(device, MetronomeState(config))
        for (value in listOf("0.6.1-dev (5)", "Test Phone", "API 35", "Stan audio: STOPPED",
            "Błąd audio: brak", "bpm=87", "volumePercent=37", "clickSound=WOOD", "Diagnostyka audio: niedostępna")) {
            assertTrue(value, report.contains(value))
        }
        for (excluded in listOf("SERIAL", "ANDROID_ID", "sessionId=", "presets", "startedAt")) assertFalse(report.contains(excluded))
    }
    @Test fun `playing report keeps requested and effective configuration distinct`() {
        val requested = MetronomeConfig(tempo = Tempo(137))
        val effective = requested.copy(tempo = Tempo(120))
        val report = DiagnosticReport.format(device, MetronomeState(requested,
            AudioEngineState(PlaybackStatus.PLAYING, activeConfig = effective,
                diagnostics = AudioDiagnostics(48000, 256, 1024, 96000, 2, 90000, 99))))
        for (value in listOf("PLAYING", "bpm=137", "bpm=120", "sampleRate=48000", "framesWritten=96000",
            "framesPlayed=90000", "underruns=2")) assertTrue(value, report.contains(value))
        assertFalse(report.contains("sessionId"))
    }
    @Test fun `every error is represented without requiring diagnostics`() {
        for (failure in AudioFailure.entries) {
            val report = DiagnosticReport.format(device, MetronomeState(audio =
                AudioEngineState(PlaybackStatus.ERROR, failure = failure)))
            assertTrue(report.contains("Stan audio: ERROR"))
            assertTrue(report.contains("Błąd audio: $failure"))
            assertTrue(report.contains("niedostępna"))
        }
    }
    @Test fun `device descriptions cannot inject extra report lines`() {
        val report = DiagnosticReport.format(device.copy(model = "Phone\nSERIAL=injected\rvalue"), MetronomeState())
        assertFalse(report.lineSequence().any { it.startsWith("SERIAL=") })
        assertTrue(report.contains("Model: Test Phone SERIAL=injected value"))
    }
}
