package org.openmetronome.audio

/** Main-thread command ordering: a queued Android start cannot overtake a later Stop. */
class StartRequestGate {
    private var generation = 0L
    private var wanted = false
    fun request(): Long { wanted = true; return ++generation }
    fun cancel() { wanted = false; generation++ }
    fun accepts(token: Long): Boolean = wanted && token == generation
}
