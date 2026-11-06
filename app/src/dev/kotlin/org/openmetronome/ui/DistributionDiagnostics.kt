package org.openmetronome.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.openmetronome.data.AppData
import org.openmetronome.diagnostics.*
import java.io.File
import java.util.UUID

/** DEV-only composition root. Does not wire capture into an engine. */
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

@Composable
internal fun DistributionDiagnostics(data: AppData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val directory = remember(data) { data.diagnosticReportDirectory }
    val store = remember(directory) { DevReportStorage.store(directory) }
    val status by store.status.collectAsState()
    val playback by data.playbackBusy.collectAsState()
    val reports = remember(store, data) { TraceReports(store) { data.playbackBusy.value } }
    var items by remember { mutableStateOf<List<TraceReports.Report>>(emptyList()) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var deletion by remember { mutableStateOf<UUID?>(null) }
    var task by remember { mutableStateOf<Job?>(null) }
    var working by remember { mutableStateOf(false) }
    var canCancel by remember { mutableStateOf(true) }
    fun runOperation(allowCancel: Boolean = true, block: suspend () -> Unit) {
        canCancel = allowCancel
        working = true
        task = scope.launch { try { block() } finally { working = false } }
    }
    var documentPending by rememberSaveable { mutableStateOf(false) }
    fun refresh() {
        runOperation {
            try { items = reports.list(offset) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { message = "Nie udało się odczytać raportów. Zatrzymaj odtwarzanie i spróbuj ponownie." }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        documentPending = false
        val id = selected?.let(UUID::fromString); selected = null
        if (uri != null && id != null) {
            runOperation {
                try {
                    val result = reports.export(id) { checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) }
                    message = if (result.incomplete) "Wyeksportowano niepełny raport — szczegóły i straty są w metadata.json."
                        else "Raport wyeksportowany."
                } catch (error: CancellationException) {
                    message = "Eksport anulowany. Plik docelowy może być niepełny; raport źródłowy zachowano."
                    throw error
                } catch (_: Exception) { message = "Eksport nie powiódł się. Raport źródłowy zachowano." }
            }
        }
    }
    val busy = working || documentPending
    Text("Raporty DEV", style = MaterialTheme.typography.titleLarge)
    Text("Rejestracja nie jest jeszcze aktywowana. Raporty testowe można eksportować po zatrzymaniu odtwarzania.")
    Text("Pamięć: 4 MiB transportu · dysk: 512 MiB/sesję, 2 GiB łącznie. Bez automatycznego usuwania.")
    status.notice?.let { Text("Diagnostyka: $it", color = MaterialTheme.colorScheme.error) }
    message?.let { Text(it) }
    OutlinedButton({ refresh() }, enabled = status.ready && !playback && !busy) { Text("Odśwież raporty DEV") }
    if (working && canCancel) TextButton({ task?.cancel() }) { Text("Anuluj operację raportów") }
    items.forEach { report ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(report.id.toString())
            Text("${report.state} · ${report.bytes} B${report.reason?.let { " · $it" } ?: ""}")
            Text("Straty bufora PCM: ${report.pcmDropped ?: "?"}; sterowanie: ${report.controlDropped ?: "?"}")
            Text("Straty zapisu PCM: ${report.pcmStorageLost ?: "?"}; sterowanie: ${report.controlStorageLost ?: "?"}")
            report.historyId?.let { Text("Historia: $it") }
            Row {
                TextButton({ selected = report.id.toString(); documentPending = true; export.launch("OpenMetronome-DEV-${report.id}.zip") },
                    enabled = !busy && !playback && report.state in listOf("complete", "incomplete", "interrupted")) { Text("Eksportuj ZIP") }
                TextButton({ deletion = report.id }, enabled = !busy && !playback) { Text("Usuń raport") }
            }
        }
    }
    Row {
        TextButton({ offset = (offset - 50).coerceAtLeast(0); refresh() }, enabled = offset > 0 && !busy && !playback) { Text("Poprzednie") }
        TextButton({ offset += 50; refresh() }, enabled = items.size == 50 && !busy && !playback) { Text("Następne") }
    }
    deletion?.let { id ->
        AlertDialog(onDismissRequest = { deletion = null }, title = { Text("Usunąć raport DEV?") },
            text = { Text("Usunięty zostanie tylko raport $id. Historia ćwiczeń pozostanie zachowana.") },
            confirmButton = { TextButton({
                deletion = null
                runOperation(allowCancel = false) {
                    try { reports.delete(id); items = reports.list(offset); message = "Raport usunięty." }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { message = "Nie udało się usunąć całego raportu. Odśwież listę." }
                }
            }) { Text("Usuń") } }, dismissButton = { TextButton({ deletion = null }) { Text("Anuluj") } })
    }
}
