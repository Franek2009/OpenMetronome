package org.openmetronome.domain.audio

import java.util.concurrent.CopyOnWriteArrayList
import org.openmetronome.domain.model.MetronomeConfig

/** JVM-only observer; never packaged in an APK. */
class CollectingPcmTrace : PcmTrace({ 1L }) {
    data class Entry(val kind: PcmTraceKind, val frame: Long,
        val a: Long, val b: Long, val c: Long, val d: Long, val config: MetronomeConfig?)
    val entries = CopyOnWriteArrayList<Entry>()
    override fun append(sequence: Long, nanos: Long, kind: PcmTraceKind, frame: Long,
        a: Long, b: Long, c: Long, d: Long, config: MetronomeConfig?): Boolean {
        entries.add(Entry(kind, frame, a, b, c, d, config))
        return true
    }
}
