package org.openmetronome.diagnostics

import android.content.Context
import android.os.Build
import java.util.UUID
import org.openmetronome.BuildConfig
import org.openmetronome.data.AppData
import org.openmetronome.domain.audio.PcmTrace
import org.openmetronome.domain.model.MetronomeConfig

/** Enabled after exact-head ART qualification; published beta.1 remains unchanged. */
internal const val DEV_CAPTURE_QUALIFIED = true

internal fun engineDiagnosticHooks(context: Context, data: AppData): EngineDiagnosticHooks {
    if (!DEV_CAPTURE_QUALIFIED) return EngineDiagnosticHooks()
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION") val versionCode = info.versionCode.toLong()
    val environment = TraceEnvironment(info.versionName ?: "unknown", versionCode, BuildConfig.SOURCE_REVISION,
        Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, "app_session")
    val bridge = DevCaptureBridge(DevReportStorage.store(data.diagnosticReportDirectory), environment) {
        data.error.value = "Metronom działa, ale nie można rozpocząć pełnej diagnostyki DEV. Sprawdź status raportów."
    }
    return EngineDiagnosticHooks(bridge::trace, bridge)
}

/** Control-thread registry; the PCM worker only ever sees its own preallocated trace. */
internal class DevCaptureBridge(private val store: TraceSessionStore, private val environment: TraceEnvironment,
    private val unavailable: () -> Unit = {}) : ControlObservation() {
    private val engineInstance = UUID.randomUUID()
    private val captures = mutableMapOf<Long, TraceCapture>()
    private val attached = mutableSetOf<Long>()
    private data class Event(val kind: TraceControlKind, val a: Long, val b: Long, val c: Long, val d: Long, val config: MetronomeConfig?, val nanos: Long)
    private val pending = ArrayDeque<Event>(32)
    private val pendingLost = LongArray(TraceControlKind.entries.size)
    private var failedAttempt = 0L

    private fun begin(id: Long): TraceCapture? {
        val origin = if (id < 0) environment.captureOrigin + "_start_failure" else environment.captureOrigin
        val capture = store.begin(TraceSessionKey(engineInstance, id), environment.copy(captureOrigin = origin)) ?: return null
        captures[id] = capture
        TraceControlKind.entries.forEach { kind ->
            val count = pendingLost[kind.ordinal]
            if (count > 0) capture.controls.noteDropped(kind, count)
            pendingLost[kind.ordinal] = 0
        }
        while (pending.isNotEmpty()) {
            val item = pending.removeFirst()
            capture.controls.offer(item.kind, item.a, item.b, item.c, item.d, item.config, item.nanos)
        }
        return capture
    }

    @Synchronized fun trace(id: Long): PcmTrace? {
        val capture = captures[id] ?: begin(id)
        if (capture == null) { unavailable(); return null }
        attached.add(id)
        return capture.trace
    }

    override fun append(sessionId: Long, kind: TraceControlKind, a: Long, b: Long, c: Long, d: Long, config: MetronomeConfig?) = synchronized(this) {
        val nanos = System.nanoTime()
        var capture = captures[sessionId]
        if (kind == TraceControlKind.START_REQUESTED && capture == null) {
            capture = begin(sessionId)
            if (capture == null) unavailable()
        }
        if (capture != null) {
            capture.controls.offer(kind, a, b, c, d, config, nanos)
            if (kind == TraceControlKind.START_REJECTED && a == 3L && sessionId !in attached) closeSession(sessionId)
        } else if (kind == TraceControlKind.START_REJECTED && a == 4L) {
            // Foreground promotion failed before an audio session existed.
            val failed = begin(--failedAttempt)
            if (failed != null) {
                failed.controls.offer(kind, a, b, c, d, config, nanos)
                closeSession(failed.key.sessionId)
            } else unavailable()
        } else {
            if (pending.size == 32) {
                val lost = pending.removeFirst(); pendingLost[lost.kind.ordinal]++
            }
            pending.addLast(Event(kind, a, b, c, d, config, nanos))
        }
        Unit
    }

    override fun closeSession(sessionId: Long) = synchronized(this) {
        val capture = captures.remove(sessionId) ?: return@synchronized
        // Closing an unattached trace is safe: no PCM producer has received it.
        if (!attached.remove(sessionId)) capture.trace.close()
        capture.finishControls()
    }
}
