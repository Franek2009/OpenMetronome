package org.openmetronome.audio

import org.junit.Assert.*
import org.junit.Test

class StartRequestGateTest {
    @Test fun `stop rejects an already queued start`() {
        val gate = StartRequestGate()
        val token = gate.request()
        assertTrue(gate.accepts(token))
        gate.cancel()
        assertFalse(gate.accepts(token))
    }
    @Test fun `only latest start request can initialize playback`() {
        val gate = StartRequestGate()
        val first = gate.request()
        val second = gate.request()
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(second))
    }
    @Test fun `new service instance does not accept an old pending request`() {
        val token = StartRequestGate().request()
        assertFalse(StartRequestGate().accepts(token))
    }
}
