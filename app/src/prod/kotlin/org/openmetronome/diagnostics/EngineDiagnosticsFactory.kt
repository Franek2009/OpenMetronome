package org.openmetronome.diagnostics

import android.content.Context
import org.openmetronome.data.AppData

internal fun engineDiagnosticHooks(context: Context, data: AppData) = EngineDiagnosticHooks()
