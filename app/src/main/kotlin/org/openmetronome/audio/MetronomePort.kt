package org.openmetronome.audio

import kotlinx.coroutines.flow.StateFlow
import org.openmetronome.domain.model.MetronomeConfig

/** UI commands; the Android implementation connects to the service-owned controller. */
interface MetronomePort : AutoCloseable {
    val state: StateFlow<MetronomeState>
    fun editConfig(transform: (MetronomeConfig) -> MetronomeConfig)
    fun start()
    fun stop()
}
