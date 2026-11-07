package org.openmetronome.diagnostics

import java.io.File

/** One process-owned store shared by service capture and DEV report UI. */
internal object DevReportStorage {
    @Volatile private var instance: TraceSessionStore? = null
    private var owner: File? = null
    @Synchronized fun store(directory: File): TraceSessionStore {
        // Instrumentation supplies its own UUID-scoped directory, never the app's store.
        if (owner != directory) {
            check(instance?.status?.value?.active in listOf(null, 0)) { "diagnostic_owner_busy" }
            instance?.close()
            instance = TraceSessionStore(directory)
            owner = directory
        }
        return checkNotNull(instance)
    }
}

