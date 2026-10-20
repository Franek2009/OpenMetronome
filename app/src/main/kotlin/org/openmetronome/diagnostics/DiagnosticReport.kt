package org.openmetronome.diagnostics

import org.openmetronome.audio.MetronomeState
import org.openmetronome.data.ConfigCodec

/** Deliberate allowlist. No Context, device identifiers, user text, presets, or history. */
data class DiagnosticDeviceInfo(
    val versionName: String, val versionCode: Long, val manufacturer: String,
    val model: String, val androidRelease: String, val api: Int,
)

object DiagnosticReport {
    fun format(device: DiagnosticDeviceInfo, state: MetronomeState): String = buildString {
        fun line(value: String) = value.replace('\n', ' ').replace('\r', ' ').take(160)
        appendLine("OpenMetronome — raport diagnostyczny")
        appendLine("Wersja: ${line(device.versionName)} (${device.versionCode})")
        appendLine("Model: ${line(device.manufacturer)} ${line(device.model)}")
        appendLine("Android: ${line(device.androidRelease)} / API ${device.api}")
        appendLine("Stan audio: ${state.audio.status}")
        appendLine("Błąd audio: ${state.audio.failure ?: "brak"}")
        appendLine("\nKonfiguracja żądana:")
        appendLine(ConfigCodec.encode(state.config))
        state.audio.activeConfig?.let {
            appendLine("\nKonfiguracja efektywna renderera:"); appendLine(ConfigCodec.encode(it))
        }
        state.audio.diagnostics?.let {
            appendLine("\nDiagnostyka audio:")
            appendLine("sampleRate=${it.sampleRate}")
            appendLine("blockFrames=${it.blockFrames}")
            appendLine("bufferFrames=${it.bufferFrames}")
            appendLine("framesWritten=${it.framesWritten}")
            appendLine("framesPlayed=${it.framesPlayed}")
            appendLine("underruns=${it.underruns}")
        } ?: appendLine("\nDiagnostyka audio: niedostępna")
        appendLine("\nMigawka z chwili otwarcia. Stan renderera nie jest pomiarem wyjścia głośnika.")
    }
}
