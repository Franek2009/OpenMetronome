package org.openmetronome.audio

/** A memory-only snapshot held for the entire session. Edits cannot invalidate a running bank. */
fun interface SampleBankSource {
    fun acquire(): SampleBankLease
}

class SampleBankLease(val samples: Map<String, ShortArray>, private val release: () -> Unit = {}) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}
