package org.openmetronome

/** Forced platform failure for silent instrumentation only; never included in release. */
class FailingForegroundService : org.openmetronome.platform.audio.MetronomeService() {
    override fun promoteToForeground() { throw SecurityException("Forced foreground failure") }
}
