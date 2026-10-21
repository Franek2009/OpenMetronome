package org.openmetronome.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.*
import java.time.format.DateTimeFormatter
import org.openmetronome.data.*
import org.openmetronome.R
import org.openmetronome.diagnostics.*
import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import org.openmetronome.domain.model.MetronomeConfig

@Composable
fun OpenMetronomeApp(model: MetronomeViewModel, data: AppData, notificationPermission: (() -> Unit)? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    val error by data.error.collectAsStateWithLifecycle()
    val sampleWorking by data.sampleWorking.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("Metronom") }
    var report by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != "Metronom" && report == null) { page = "Metronom" }
    val context = LocalContext.current
    val diagnosticsTitle = stringResource(R.string.diagnostics_title)
    Scaffold(modifier = Modifier.imePadding(), bottomBar = { PlaybackControls(state, model::start, model::stop, startEnabled = !sampleWorking) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppNavigation(page) { page = it }
            error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                TextButton({ data.error.value = null }) { Text("Zamknij komunikat") }
            }
            Box(Modifier.weight(1f)) {
                when (page) {
                    "Presety" -> PresetsScreen(data, model)
                    "Statystyki" -> StatisticsScreen(data)
                    "Ustawienia" -> SettingsScreen(model, data, notificationPermission) {
                        @Suppress("DEPRECATION")
                        val info = context.packageManager.getPackageInfo(context.packageName, 0)
                        @Suppress("DEPRECATION")
                        val code = info.versionCode.toLong()
                        report = DiagnosticReport.format(DiagnosticDeviceInfo(info.versionName ?: "?", code,
                            Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT), model.state.value)
                    }
                    else -> MetronomeScreen(
                        state, model::setTempo, model::increaseTempo, model::decreaseTempo,
                        model::setTimeSignature, model::start, model::stop, model::setConfig,
                        onTapTempo = model::tapTempo, onResetTapTempo = model::resetTapTempo,
                        showPlaybackControls = false,
                        onAdjustTempo = model::adjustTempo,
                        onBeatAccent = model::cycleBeatAccent,
                    )
                }
            }
        }
    }
    report?.let { snapshot ->
        DiagnosticsDialog(snapshot, { report = null }) { text ->
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(diagnosticsTitle, text))
        }
    }
}

@Composable
internal fun AppNavigation(page: String, select: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically) {
            PageButtons(page, select)
            IconButton({ select("Presety") }, Modifier.semantics { contentDescription = "Presety" }) {
                Canvas(Modifier.size(24.dp)) {
                    val ink = Color(0xFFD2E1D6)
                    drawRect(ink, style = Stroke(2.dp.toPx()))
                    drawLine(ink, androidx.compose.ui.geometry.Offset(size.width*.25f, size.height*.35f),
                        androidx.compose.ui.geometry.Offset(size.width*.75f, size.height*.35f), 2.dp.toPx())
                    drawLine(ink, androidx.compose.ui.geometry.Offset(size.width*.25f, size.height*.65f),
                        androidx.compose.ui.geometry.Offset(size.width*.75f, size.height*.65f), 2.dp.toPx())
                }
            }
        }
    }
}

@Composable
private fun PageButtons(page: String, select: (String) -> Unit) {
    listOf("Metronom", "Statystyki", "Ustawienia").forEach { target ->
        TextButton({ select(target) }, Modifier.sizeIn(minHeight = 48.dp).semantics {
            selected = page == target
            role = Role.Tab
        }) {
            Text(target, color = if (page == target) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PresetsScreen(data: AppData, model: MetronomeViewModel) {
    val presets by data.presets.collectAsStateWithLifecycle(initialValue = emptyList())
    var name by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Preset?>(null) }
    var deleting by remember { mutableStateOf<Preset?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Presety", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(name, { name = it.take(120) }, label = { Text("Nazwa presetu") }, singleLine = true)
        Button({ data.savePreset(name, model.state.value.config); name = ""; selected = null }, enabled = name.isNotBlank()) { Text("Zapisz jako nowy") }
        selected?.let { preset ->
            Text("Edytujesz: ${preset.name}")
            TextButton({ data.renamePreset(preset, name); selected = null }, enabled = name.isNotBlank()) { Text("Zmień nazwę") }
            TextButton({ data.savePreset(name, model.state.value.config, preset); selected = null }, enabled = name.isNotBlank()) { Text("Zastąp bieżącymi ustawieniami") }
        }
        if (presets.isEmpty()) Text("Nie zapisano jeszcze presetów.")
        presets.forEach { preset ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(preset.name, style = MaterialTheme.typography.titleMedium)
                    Row {
                        TextButton({
                            try { model.setConfig(ConfigCodec.decode(preset.config)) }
                            catch (_: Exception) { data.error.value = "Nie można odczytać tego presetu. Nie został zmieniony." }
                        }) { Text("Wczytaj") }
                        TextButton({ selected = preset; name = preset.name }) { Text("Edytuj") }
                        TextButton({ deleting = preset }) { Text("Usuń") }
                    }
                }
            }
        }
    }
    deleting?.let { preset ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Usunąć preset?") },
            text = { Text("${preset.name}\nHistoria ćwiczeń pozostanie zachowana.") },
            confirmButton = { TextButton({ data.deletePreset(preset); deleting = null; if (selected?.id == preset.id) selected = null }) { Text("Usuń") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Anuluj") } })
    }
}

@Composable
private fun StatisticsScreen(data: AppData) {
    val exporting by data.exporting.collectAsStateWithLifecycle()
    val notice by data.exportNotice.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) data.exportCsv { checkNotNull(resolver.openOutputStream(uri, "wt")) { "Cannot open document" } }
    }
    val sessions by data.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val total by data.total.collectAsStateWithLifecycle(initialValue = PracticeTotal(0, 0))
    val days by data.days.collectAsStateWithLifecycle(initialValue = emptyList())
    val weeks = remember(days) { days.groupBy { LocalDate.parse(it.date).with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        .mapValues { (_, entries) -> entries.sumOf { it.durationMs } }.toSortedMap(compareByDescending { it }) }
    fun duration(ms: Long) = "${ms / 3600000} h ${(ms / 60000) % 60} min ${(ms / 1000) % 60} s"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Statystyki", style = MaterialTheme.typography.headlineMedium)
        OutlinedButton({ export.launch("OpenMetronome-sesje-${LocalDate.now()}.csv") }, enabled = !exporting) {
            Text(if (exporting) "Zapisywanie CSV…" else "Eksportuj CSV")
        }
        notice?.let { Text(it) }
        Text("CSV zawiera wszystkie sesje, także trwającą według ostatniego zapisu. To nie jest kopia do importu.",
            style = MaterialTheme.typography.bodySmall)
        Text("Sesje: ${total.sessions}")
        Text("Czas ćwiczenia: ${duration(total.durationMs)}")
        Text("Dziennie", style = MaterialTheme.typography.titleLarge)
        days.take(14).forEach { Text("${it.date}: ${duration(it.durationMs)}") }
        Text("Tygodniowo (od poniedziałku)", style = MaterialTheme.typography.titleLarge)
        weeks.entries.take(12).forEach { Text("${it.key}: ${duration(it.value)}") }
        Text("Historia — ostatnie 100 sesji", style = MaterialTheme.typography.titleLarge)
        sessions.forEach { session ->
            val date = Instant.ofEpochMilli(session.startedAt).atZone(ZoneId.of(session.zoneId)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            Text("$date · ${duration(session.durationMs)} · ${when { session.interrupted -> "przerwana"; session.finished -> "zakończona"; else -> "w toku" }}")
        }
        if (total.sessions == 0L) Text("Rozpocznij ćwiczenie, aby zapisać pierwszą sesję.")
        Text("Planowana cisza jest częścią ćwiczenia. Zapis co 5 s; przy awarii ostatnie sekundy mogą nie zostać zapisane.")
    }
}

@Composable
private fun SettingsScreen(model: MetronomeViewModel, data: AppData,
    notificationPermission: (() -> Unit)?, diagnostics: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val config = state.config
    val onChange = model::setConfig
    val samples by data.samples.items.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Ustawienia", style = MaterialTheme.typography.headlineMedium)
        SoundControls(config, onChange, customSamples = samples)
        SampleLibraryControls(model, data)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wizualny puls", Modifier.weight(1f))
            Switch(config.visualPulse, { onChange(config.copy(visualPulse = it)) },
                Modifier.semantics { contentDescription = "Wizualny puls" })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wibracje", Modifier.weight(1f))
            Switch(config.vibration, { onChange(config.copy(vibration = it)) },
                Modifier.semantics { contentDescription = "Wibracje" })
        }
        notificationPermission?.let { TextButton(it) { Text("Zezwól na powiadomienia") } }
        OutlinedButton(diagnostics) { Text(stringResource(R.string.diagnostics_open)) }
        Text("Ciemny motyw · dane tylko na urządzeniu", style = MaterialTheme.typography.bodySmall)
    }
}
