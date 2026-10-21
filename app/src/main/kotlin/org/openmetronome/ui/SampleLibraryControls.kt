package org.openmetronome.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openmetronome.data.AppData
import org.openmetronome.domain.model.ClickSound
import org.openmetronome.platform.audio.AndroidSampleDecoder

@Composable
fun SampleLibraryControls(model: MetronomeViewModel, data: AppData) {
    val context = LocalContext.current.applicationContext
    val samples by data.samples.items.collectAsStateWithLifecycle()
    val working by data.sampleWorking.collectAsStateWithLifecycle()
    val notice by data.sampleNotice.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val stopped = !state.keepsScreenOn && state.audio.status != org.openmetronome.audio.PlaybackStatus.STOPPING
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val decoder = AndroidSampleDecoder(context)
            data.importSample({ decoder.name(uri) }, { decoder.decode(uri) }) { id ->
                val current = model.state.value.config
                model.setConfig(current.copy(sound = current.sound.copy(clickSound = ClickSound.IMPULSE_FULL, customSampleId = id)))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Moje sample (${samples.size}/32)", style = MaterialTheme.typography.titleMedium)
        OutlinedButton({ importer.launch(arrayOf("audio/*", "application/ogg")) }, enabled = stopped && !working && samples.size < 32) {
            Text(if (working) "Przygotowywanie sampla…" else "Importuj sample")
        }
        Text("WAV, MP3 lub OGG, maks. 10 MiB i 10 s. Import usuwa ciszę, skraca do 40 ms i tworzy wyższy akcent. Plik źródłowy pozostaje bez zmian.", style = MaterialTheme.typography.bodySmall)
        if (!stopped) Text("Zatrzymaj metronom, aby importować lub edytować bibliotekę.")
        notice?.let { Text(it) }
        samples.forEach { sample ->
            Row(Modifier.fillMaxWidth()) {
                Text(if (sample.available) sample.name else "Brak pliku: ${sample.name}", Modifier.weight(1f))
                TextButton({ renameId = sample.id; renameText = sample.name }, enabled = stopped && !working,
                    modifier = Modifier.semantics { contentDescription = "Zmień nazwę sampla ${sample.name}" }) { Text("Nazwa") }
                TextButton({ deleteId = sample.id }, enabled = stopped && !working,
                    modifier = Modifier.semantics { contentDescription = "Usuń sample ${sample.name}" }) { Text("Usuń") }
            }
        }
    }
    renameId?.let { id ->
        AlertDialog(onDismissRequest = { renameId = null }, title = { Text("Nazwa sampla") },
            text = { OutlinedTextField(renameText, { renameText = it.take(80) }, label = { Text("Nazwa") }, singleLine = true) },
            confirmButton = { TextButton({ data.renameSample(id, renameText); renameId = null }, enabled = stopped && renameText.isNotBlank() && !working) { Text("Zapisz") } },
            dismissButton = { TextButton({ renameId = null }) { Text("Anuluj") } })
    }
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Usunąć sample?") },
            text = { Text("Usunięta zostanie tylko kopia w aplikacji. Sample używany w presecie nie może zostać usunięty. Bieżący wybór wróci do brzmienia Pełny.") },
            confirmButton = { TextButton({
                data.deleteSample(id) {
                    val current = model.state.value.config
                    if (current.sound.customSampleId == id) model.setConfig(current.copy(sound = current.sound.copy(customSampleId = null, clickSound = ClickSound.IMPULSE_FULL)))
                }
                deleteId = null
            }, enabled = stopped && !working) { Text("Usuń") } },
            dismissButton = { TextButton({ deleteId = null }) { Text("Anuluj") } })
    }
}
