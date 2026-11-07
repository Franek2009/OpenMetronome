package org.openmetronome.diagnostics

import org.openmetronome.domain.audio.PcmTrace

/** Flavor composition only: PROD supplies no recorder and no disk-backed diagnostics. */
data class EngineDiagnosticHooks(val traceFactory: ((Long) -> PcmTrace?)? = null,
    val controls: ControlObservation? = null)
