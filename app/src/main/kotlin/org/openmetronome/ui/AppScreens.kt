package org.openmetronome.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.font.FontWeight
import androidx.core.net.toUri
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.TempoPercent
import org.openmetronome.domain.model.TempoChange
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
fun OpenMetronomeApp(model: MetronomeViewModel, data: AppData, notificationPermission: (() -> Unit)? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    val error by data.error.collectAsStateWithLifecycle()
    val sampleWorking by data.sampleWorking.collectAsStateWithLifecycle()
    val backupWorking by data.backupWorking.collectAsStateWithLifecycle()
    val recoveryRequired by data.recoveryRequired.collectAsStateWithLifecycle()
    val storageReady by data.storageReady.collectAsStateWithLifecycle()
    val historyExporting by data.exporting.collectAsStateWithLifecycle()
    val display by data.displayPreferences.collectAsStateWithLifecycle(initialValue = PracticeDisplayPreferences())
    val customSamples by data.samples.items.collectAsStateWithLifecycle()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(view, fullscreen, state.keepsScreenOn, display.keepFullscreenAwake) {
        val old = view.keepScreenOn
        view.keepScreenOn = state.keepsScreenOn && (!fullscreen || display.keepFullscreenAwake)
        onDispose { view.keepScreenOn = old }
    }
    DisposableEffect(view, fullscreen) {
        val activity = view.context as? android.app.Activity
        val bars = activity?.let { WindowInsetsControllerCompat(it.window, view) }
        if (fullscreen) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (fullscreen) bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val canStart = storageReady && !sampleWorking && !backupWorking && !recoveryRequired && !historyExporting
    if (fullscreen) {
        FullscreenPractice(state, model::start, model::stop, { fullscreen = false }, canStart, display.reduceMotion)
        return
    }
    var page by rememberSaveable { mutableStateOf("Metronom") }
    var report by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != "Metronom" && report == null) { page = "Metronom" }
    val context = LocalContext.current
    val diagnosticsTitle = stringResource(R.string.diagnostics_title)
    Scaffold(modifier = Modifier.imePadding(), bottomBar = { PlaybackControls(state, model::start, model::stop, startEnabled = canStart) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppNavigation(page) { page = it }
            error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                TextButton({ data.error.value = null }) { Text("Zamknij komunikat") }
            }
            Box(Modifier.weight(1f)) {
                if (!storageReady) {
                    Text(if (recoveryRequired) "Dane wymagają odzyskania. Start i edycja są zablokowane."
                        else "Przygotowywanie danych…", modifier = Modifier.padding(16.dp))
                } else when (page) {
                    "Presety" -> PresetsScreen(data, model)
                    "Statystyki" -> StatisticsScreen(data, model)
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
                        onFullscreen = { fullscreen = true },
                        onPresets = { page = "Presety" }, customSamples = customSamples,
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
    val editorFocus = remember { FocusRequester() }
    // Keep Done within Compose: Oreo can restore cleared window focus to its sole editor.
    Column(Modifier.fillMaxSize().focusRequester(editorFocus).focusable().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Presety", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(name, { name = it.take(120) }, label = { Text("Nazwa presetu") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { editorFocus.requestFocus() }))
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
private fun StatisticsScreen(data: AppData, model: MetronomeViewModel) {
    val exporting by data.exporting.collectAsStateWithLifecycle()
    val notice by data.exportNotice.collectAsStateWithLifecycle()
    val backupWorking by data.backupWorking.collectAsStateWithLifecycle()
    val backupNotice by data.backupNotice.collectAsStateWithLifecycle()
    val playbackBusy by data.playbackBusy.collectAsStateWithLifecycle()
    val sampleWorking by data.sampleWorking.collectAsStateWithLifecycle()
    val recoveryRequired by data.recoveryRequired.collectAsStateWithLifecycle()
    val audioState by model.state.collectAsStateWithLifecycle()
    val backupEnabled = !backupWorking && !exporting && !playbackBusy && !sampleWorking &&
        !recoveryRequired && audioState.audio.status in listOf(org.openmetronome.audio.PlaybackStatus.STOPPED, org.openmetronome.audio.PlaybackStatus.ERROR)
    val resolver = LocalContext.current.contentResolver
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) data.exportCsv { checkNotNull(resolver.openOutputStream(uri, "wt")) { "Cannot open document" } }
    }
    var legacyBackup by rememberSaveable { mutableStateOf(false) }
    var legacyWarning by rememberSaveable { mutableStateOf(false) }
    val backupExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            val output = { checkNotNull(resolver.openOutputStream(uri, "wt")) }
            if (legacyBackup) data.exportLegacyBackup(output) else data.exportBackup(output)
        }
    }
    var pendingImportPath by rememberSaveable { mutableStateOf<String?>(null) }
    val backupImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingImportPath = uri?.toString() }
    var historyRetry by remember { mutableIntStateOf(0) }
    val historySource = remember(data, historyRetry) { data.statisticsHistory() }
    val history by historySource.collectAsStateWithLifecycle(initialValue = StatisticsHistory.Loading)
    val preferencesSource = remember(data, historyRetry) { data.statisticsPreferences() }
    val preferenceState by preferencesSource.collectAsStateWithLifecycle(initialValue = null)
    val preferences = preferenceState?.getOrNull()
    val today by rememberPracticeDate()
    val calculation by produceState<Result<PracticeAnalytics>?>(null, history, preferences, today) {
        value = null
        val ready = history as? StatisticsHistory.Ready
        if (ready != null && preferences != null) value = try { Result.success(data.calculateAnalytics(ready.sessions, preferences, today)) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
    }
    val analytics = calculation?.getOrNull()
    val sessions = (history as? StatisticsHistory.Ready)?.sessions ?: emptyList()
    var statisticsPage by rememberSaveable { mutableIntStateOf(0) }
    var thresholdsOpen by rememberSaveable { mutableStateOf(false) }
    val days = analytics?.days ?: emptyList()
    val weeks = remember(days) { days.groupBy { LocalDate.parse(it.date).with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        .mapValues { (_, entries) -> entries.sumOf { it.durationMs } }.toSortedMap(compareByDescending { it }) }
    fun duration(ms: Long): String = when {
        ms >= 3_600_000 -> "${ms / 3_600_000} h ${(ms / 60_000) % 60} min"
        ms >= 60_000 -> "${ms / 60_000} min ${(ms / 1000) % 60} s"
        else -> "${ms / 1000} s"
    }
    fun dayCount(value: Int) = if (value == 1) "1 dzień" else "$value dni"

    var periodName by rememberSaveable { mutableStateOf(StatisticsPeriod.DAYS_7.name) }
    val period = StatisticsPeriod.valueOf(periodName)
    val chart = remember(days, period, today) { PracticeStats.chart(days, period, today) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Statystyki", style = MaterialTheme.typography.headlineMedium)
        DailyPracticeCard(data, today)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Podsumowanie", "Historia", "Analiza").forEachIndexed { index, label ->
                FilterChip(statisticsPage == index, { statisticsPage = index }, label = { Text(label) })
            }
        }
        TextButton({ thresholdsOpen = true }, enabled = preferences != null) {
            Text(preferences?.let { "Progi: sesja ${it.minimumSessionSeconds} s · dzień ${it.activeDayMinutes} min" } ?: "Odczytywanie progów…")
        }
        if (history == StatisticsHistory.Unavailable || calculation?.isFailure == true || preferenceState?.isFailure == true) {
            Text("Nie udało się odczytać lub przeliczyć statystyk. To nie oznacza zerowego czasu.", color = MaterialTheme.colorScheme.error)
            TextButton({ historyRetry++ }) { Text("Ponów odczyt statystyk") }
        } else if (analytics == null) Text("Wczytywanie statystyk…")
        if (analytics != null && statisticsPage == 0) {
        val summary = analytics.summary
        val qualifiedTotal = analytics.qualifying.sumOf { it.durationMs }
        Text("Zaliczony czas dzisiaj: ${duration(analytics.todayMs)} · ${if (today in analytics.activeDates) "dzień aktywny" else "próg dnia jeszcze nieosiągnięty"}")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard("Łącznie", duration(qualifiedTotal), Modifier.weight(1f), "Zaliczony czas")
            SummaryCard("Sesje", analytics.qualifying.size.toString(), Modifier.weight(1f), "Spełniają próg sesji")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard("Aktualna seria", dayCount(summary.currentStreak), Modifier.weight(1f))
            SummaryCard("Najdłuższa", dayCount(summary.longestStreak), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard("Średnia sesja", duration(summary.averageSessionMs), Modifier.weight(1f))
            SummaryCard("Najlepszy dzień", summary.bestDay?.let { duration(it.durationMs) } ?: "—", Modifier.weight(1f),
                summary.bestDay?.date)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard("Ten tydzień", duration(analytics.weekMs), Modifier.weight(1f))
            SummaryCard("Ten miesiąc", duration(analytics.monthMs), Modifier.weight(1f))
        }
        Text("Aktywność", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(StatisticsPeriod.DAYS_7 to "7 dni", StatisticsPeriod.DAYS_30 to "30 dni",
                StatisticsPeriod.WEEKS_12 to "12 tygodni").forEach { (target, label) ->
                FilterChip(selected = period == target, onClick = { periodName = target.name }, label = { Text(label) })
            }
        }
        PracticeBarChart(chart, if (period == StatisticsPeriod.WEEKS_12) "min/tydzień" else "min/dzień")
        Text("Tygodniowo (od poniedziałku)", style = MaterialTheme.typography.titleLarge)
        weeks.entries.take(8).forEach { (week, value) ->
            Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Od $week", color = MaterialTheme.colorScheme.onSurfaceVariant); Text(duration(value), fontWeight = FontWeight.SemiBold)
            } }
        }
        }
        if (analytics != null && statisticsPage == 1) StatisticsHistoryPanel(sessions, analytics.activeDates, today, data::sessionTimeline)
        if (analytics != null && statisticsPage == 2) PracticeAnalysisPanel(analytics)
        if (history is StatisticsHistory.Ready && sessions.isEmpty()) Text("Rozpocznij ćwiczenie, aby zapisać pierwszą sesję.")
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Dane", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ export.launch("OpenMetronome-sesje-${LocalDate.now()}.csv") }, enabled = !exporting && !backupWorking) {
                Text(if (exporting) "Zapisywanie…" else "Eksportuj CSV")
            }
            OutlinedButton({ legacyBackup = false; backupExport.launch("OpenMetronome-${LocalDate.now()}.ombak") }, enabled = backupEnabled) {
                Text(if (backupWorking) "Przetwarzanie…" else "Kopia zapasowa")
            }
            OutlinedButton({ backupImport.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) }, enabled = backupEnabled) {
                Text("Przywróć kopię")
            }
        }
        TextButton({ legacyWarning = true }, enabled = backupEnabled) { Text("Kopia zgodna z 1.0 (v1)") }
        if (!backupEnabled && !backupWorking && !recoveryRequired) Text("Zatrzymaj metronom i poczekaj na zakończenie zapisu oraz innych operacji, aby użyć kopii zapasowej.")
        notice?.let { Text(it) }; backupNotice?.let { Text(it) }
    }
    if (thresholdsOpen && preferences != null) StatisticsThresholdDialog(preferences, { thresholdsOpen = false }, data::saveStatisticsPreferences)
    if (legacyWarning) AlertDialog(onDismissRequest = { legacyWarning = false }, title = { Text("Kopia zgodna z 1.0") },
        text = { Text("Format v1 pomija przebieg konfiguracji, progi i ustawienia ekranu. Aplikacja 1.0 ignoruje procenty i odliczanie. Nowe metra wymagają v2: eksport v1 ich nie zamieni ani nie usunie i zostanie odrzucony. Pełne dane zachowuje zwykła Kopia zapasowa v2.") },
        confirmButton = { TextButton({ legacyWarning = false; legacyBackup = true; backupExport.launch("OpenMetronome-legacy-${LocalDate.now()}.ombak") }) { Text("Eksportuj v1 z pominięciami") } },
        dismissButton = { TextButton({ legacyWarning = false }) { Text("Anuluj") } })
    pendingImportPath?.let { path ->
        val uri = path.toUri()
        AlertDialog(onDismissRequest = { pendingImportPath = null }, title = { Text("Przywrócić kopię?") },
            text = { Text("Bieżące ustawienia, presety, historia i własne sample zostaną zastąpione zawartością kopii. Najpierw warto zapisać obecną kopię.") },
            confirmButton = { TextButton({
                pendingImportPath = null
                data.importBackup({ checkNotNull(resolver.openInputStream(uri)) }, model::setConfig)
            }, enabled = backupEnabled) { Text("Przywróć") } }, dismissButton = { TextButton({ pendingImportPath = null }) { Text("Anuluj") } })
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier, detail: String? = null) {
    Card(modifier) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    } }
}

@Composable
private fun PracticeBarChart(bars: List<PracticeChartBar>, unit: String) {
    val max = bars.maxOfOrNull { it.durationMs }?.coerceAtLeast(1) ?: 1
    val primary = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription =
            bars.joinToString(", ") { "${it.label}: ${it.durationMs / 60000} minut" } }) {
            val gap = if (bars.size > 20) 2.dp.toPx() else 4.dp.toPx()
            val width = (size.width - gap * (bars.size - 1)) / bars.size
            bars.forEachIndexed { index, bar ->
                val fraction = bar.durationMs.toFloat() / max.toFloat()
                val height = if (bar.durationMs == 0L) 3.dp.toPx() else (size.height * fraction).coerceAtLeast(6.dp.toPx())
                drawRoundRect(if (bar.durationMs == 0L) quiet else primary,
                    topLeft = androidx.compose.ui.geometry.Offset(index * (width + gap), size.height - height),
                    size = androidx.compose.ui.geometry.Size(width, height), cornerRadius = CornerRadius(4.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(bars.firstOrNull()?.label.orEmpty(), style = MaterialTheme.typography.labelSmall)
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(bars.lastOrNull()?.label.orEmpty(), style = MaterialTheme.typography.labelSmall)
        }
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
        val display by data.displayPreferences.collectAsStateWithLifecycle(initialValue = PracticeDisplayPreferences())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Nie wygaszaj pełnego ekranu podczas odtwarzania", Modifier.weight(1f))
            Switch(display.keepFullscreenAwake, { data.saveDisplayPreferences(display.copy(keepFullscreenAwake = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ogranicz animacje pełnego ekranu", Modifier.weight(1f))
            Switch(display.reduceMotion, { data.saveDisplayPreferences(display.copy(reduceMotion = it)) })
        }
        Text("Zmiana tempa", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TempoChange.entries.forEach { mode ->
                FilterChip(config.tempoChange == mode, { onChange(config.copy(tempoChange = mode)) },
                    label = { Text(if (mode == TempoChange.NEXT_BAR) "Od następnego taktu" else "Od następnego pulsu") })
            }
        }
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
        DistributionDiagnostics(data)
    }
}
