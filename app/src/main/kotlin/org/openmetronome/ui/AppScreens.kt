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
import kotlinx.coroutines.launch
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
import androidx.compose.ui.platform.testTag
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.TempoPercent
import org.openmetronome.domain.model.TempoChange
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.openmetronome.widget.WidgetConfiguration
import org.openmetronome.widget.WidgetShortcut

@Composable
fun OpenMetronomeApp(model: MetronomeViewModel, data: AppData, widgetStatistics: Boolean = false, widgetRequest: Int = 0, onWidgetRequestHandled: (Int) -> Unit = {}, notificationPermission: (() -> Unit)? = null, widgetMusicScope: Boolean = false, widgetActivityTemplate: String? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    val error by data.error.collectAsStateWithLifecycle()
    val sampleWorking by data.sampleWorking.collectAsStateWithLifecycle()
    val backupReserved by data.backupReservation.collectAsStateWithLifecycle(data.backupLiveReserved)
    val recoveryRequired by data.recoveryRequired.collectAsStateWithLifecycle()
    val storageReady by data.storageReady.collectAsStateWithLifecycle()
    val historyExporting by data.exporting.collectAsStateWithLifecycle()
    val playbackBusy by data.playbackBusy.collectAsStateWithLifecycle()
    val sessionWriteFailed by data.sessionWriteFailed.collectAsStateWithLifecycle()
    val activityEditorVisible by data.activityEditorVisible.collectAsStateWithLifecycle()
    val latestSessions by data.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var groupingRetry by remember { mutableIntStateOf(0) }
    val groupingSource = remember(data, groupingRetry) { data.observePracticeGrouping() }
    val groupingState by groupingSource.collectAsStateWithLifecycle(initialValue = null)
    val grouping = groupingState?.getOrNull()
    val display by data.displayPreferences.collectAsStateWithLifecycle(initialValue = PracticeDisplayPreferences())
    val customSamples by data.samples.items.collectAsStateWithLifecycle()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf("Metronom") }
    var report by rememberSaveable { mutableStateOf<String?>(null) }
    var activityTemplate by rememberSaveable { mutableStateOf<String?>(null) }
    var handledWidgetRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(widgetRequest, activityTemplate, activityEditorVisible) {
        if (widgetRequest > 0 && widgetRequest != handledWidgetRequest && activityTemplate == null && !activityEditorVisible) {
            fullscreen = false
            report = null
            if (widgetActivityTemplate != null) activityTemplate = widgetActivityTemplate
            else page = if (widgetStatistics) "Statystyki" else "Metronom"
            if (widgetStatistics) { data.saveMusicScope(widgetMusicScope); data.awaitWrites() }
            handledWidgetRequest = widgetRequest
            onWidgetRequestHandled(widgetRequest)
        }
    }
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
    val context = LocalContext.current
    val request by data.backupDocumentRequest.collectAsStateWithLifecycle()
    val backupExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val resolver = context.contentResolver
        data.completeBackupDocument(uri?.let { { checkNotNull(resolver.openOutputStream(it, "wt")) } })
    }
    LaunchedEffect(request) {
        if (request != null) data.takeBackupDocumentRequest()?.let { version ->
            val suffix = if (version == 3) "" else "-v$version"
            try { backupExport.launch("OpenMetronome$suffix-${LocalDate.now()}.ombak") }
            catch (error: RuntimeException) { data.completeBackupDocument { throw error } }
        }
    }
    val canStart = storageReady && !sampleWorking && !backupReserved && !recoveryRequired && !historyExporting
    if (fullscreen) {
        FullscreenPractice(state, model::start, model::stop, { fullscreen = false }, canStart, display.reduceMotion)
        return
    }
    BackHandler(enabled = page != "Metronom" && report == null) { page = "Metronom" }
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
                    "Statystyki" -> StatisticsScreen(data, model, groupingState) { groupingRetry++ }
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
                        onFinishSession = grouping?.groups?.lastOrNull()?.takeIf { !it.completed && !playbackBusy && !sessionWriteFailed && !state.keepsScreenOn }
                            ?.playbackIds?.lastOrNull()?.takeIf { id -> latestSessions.any { it.id == id && it.finished } }?.let { id ->
                            { scope.launch { runCatching { data.previewPracticeGrouping(PracticeGroupingChange.FinishAfter(id), Instant.now()) }
                                .onSuccess { data.applyPracticeGrouping(it) }.onFailure { data.error.value = "Nie udało się zakończyć sesji." } } }
                        },
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
    activityTemplate?.takeIf { storageReady }?.let { encoded ->
        val value = remember(encoded) { WidgetConfiguration.decode(encoded) }
        ActivityDialog(null, data, WidgetShortcut(value.category, value.instrument, value.durationMinutes)) { activityTemplate = null }
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
        TextButton({ select(target) }, Modifier.sizeIn(minHeight = 48.dp).testTag("navigation-$target").semantics {
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
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = presets.firstOrNull { it.id == selectedId }
    var deleting by remember { mutableStateOf<Preset?>(null) }
    val editorFocus = remember { FocusRequester() }
    // Keep Done within Compose: Oreo can restore cleared window focus to its sole editor.
    Column(Modifier.fillMaxSize().focusRequester(editorFocus).focusable().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Presety", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(name, { name = it.take(120) }, label = { Text("Nazwa presetu") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { editorFocus.requestFocus() }))
        Button({ data.savePreset(name, model.state.value.config); name = ""; selectedId = null }, enabled = name.isNotBlank()) { Text("Zapisz jako nowy") }
        selected?.let { preset ->
            Text("Edytujesz: ${preset.name}")
            TextButton({ data.renamePreset(preset, name); selectedId = null }, enabled = name.isNotBlank()) { Text("Zmień nazwę") }
            TextButton({ data.savePreset(name, model.state.value.config, preset); selectedId = null }, enabled = name.isNotBlank()) { Text("Zastąp bieżącymi ustawieniami") }
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
                        TextButton({ selectedId = preset.id; name = preset.name }) { Text("Edytuj") }
                        TextButton({ deleting = preset }) { Text("Usuń") }
                    }
                }
            }
        }
    }
    deleting?.let { preset ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Usunąć preset?") },
            text = { Text("${preset.name}\nHistoria ćwiczeń pozostanie zachowana.") },
            confirmButton = { TextButton({ data.deletePreset(preset); deleting = null; if (selectedId == preset.id) selectedId = null }) { Text("Usuń") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Anuluj") } })
    }
}

@Composable
private fun StatisticsScreen(data: AppData, model: MetronomeViewModel,
    groupingState: Result<PracticeGroupingSummary>?, retryGrouping: () -> Unit) {
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
    var legacyWarning by rememberSaveable { mutableIntStateOf(0) }
    var pendingImportPath by rememberSaveable { mutableStateOf<String?>(null) }
    val backupImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingImportPath = uri?.toString() }
    var historyRetry by remember { mutableIntStateOf(0) }
    val historySource = remember(data, historyRetry) { data.statisticsHistory() }
    val history by historySource.collectAsStateWithLifecycle(initialValue = StatisticsHistory.Loading)
    val preferencesSource = remember(data, historyRetry) { data.statisticsPreferences() }
    val preferenceState by preferencesSource.collectAsStateWithLifecycle(initialValue = null)
    val preferences = preferenceState?.getOrNull()
    val today by rememberPracticeDate()
    val musicScope by data.settings.musicScope.collectAsStateWithLifecycle(initialValue = false)
    val musicNow = remember(today) { kotlinx.coroutines.flow.flow { while (true) { emit(Instant.now()); kotlinx.coroutines.delay(60_000) } } }
    val musicSource = remember(data, musicNow, historyRetry) { data.observeMusicStatistics(musicNow) }
    val musicState by musicSource.collectAsStateWithLifecycle(initialValue = MusicStatisticsState.Loading)
    val calculation by produceState<Result<PracticeAnalytics>?>(null, history, preferences, today) {
        value = null
        val ready = history as? StatisticsHistory.Ready
        if (ready != null && preferences != null) value = try { Result.success(data.calculateAnalytics(ready.sessions, preferences, today)) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
    }
    val analytics = calculation?.getOrNull()
    val sessions = (history as? StatisticsHistory.Ready)?.sessions ?: emptyList()
    val grouping = groupingState?.getOrNull()
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
    Column(Modifier.fillMaxSize().testTag("statistics-content").verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Statystyki", style = MaterialTheme.typography.headlineMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!musicScope, { data.saveMusicScope(false) }, label = { Text("Metronom") })
            FilterChip(musicScope, { data.saveMusicScope(true) }, label = { Text("Cała aktywność") })
        }
        if (!musicScope) DailyPracticeCard(data, today)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Podsumowanie", "Historia", "Analiza").forEachIndexed { index, label ->
                FilterChip(statisticsPage == index, { statisticsPage = index }, label = { Text(label) })
            }
        }
        if (!musicScope) TextButton({ thresholdsOpen = true }, enabled = preferences != null) {
            Text(preferences?.let { "Progi: sesja ${it.minimumSessionSeconds} s · dzień ${it.activeDayMinutes} min" } ?: "Odczytywanie progów…")
        }
        if (musicScope) {
            when (val current = musicState) {
                MusicStatisticsState.Loading -> Text("Wczytywanie statystyk…")
                MusicStatisticsState.Unavailable -> {
                    Text("Nie udało się odczytać statystyk.", color = MaterialTheme.colorScheme.error)
                    TextButton({ historyRetry++ }) { Text("Ponów odczyt statystyk") }
                }
                is MusicStatisticsState.Ready -> if (statisticsPage == 0) {
                    val summary = current.summary
                    if (summary.days.isEmpty()) Text("Brak aktywności.")
                    SummaryCard("Dzisiaj", duration(summary.todayMs), Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryCard("Ten tydzień", duration(summary.weekMs), Modifier.weight(1f))
                        SummaryCard("Ten miesiąc", duration(summary.monthMs), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryCard("Łącznie", duration(summary.totalMs), Modifier.weight(1f))
                        SummaryCard("Aktywne dni", summary.activeDays.toString(), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryCard("Seria dni", summary.dayStreak.current.toString(), Modifier.weight(1f))
                        SummaryCard("Seria tygodni", summary.currentWeek.goalDays?.let { summary.weekStreak.current.toString() } ?: "—", Modifier.weight(1f))
                    }
                    Text("Ten tydzień", style = MaterialTheme.typography.titleMedium)
                    val weekly = summary.days.filter { it.date in summary.currentWeek.monday..summary.today }
                        .map { DayTotal(it.date.toString(), it.durationMs) }
                    PracticeBarChart(PracticeStats.chart(weekly, StatisticsPeriod.DAYS_7, summary.today), "min/dzień")
                    summary.currentWeek.goalDays?.let { goal ->
                        Text("Cel tygodniowy: ${summary.currentWeek.activeDays}/$goal dni")
                        LinearProgressIndicator(progress = { (summary.currentWeek.activeDays.toFloat() / goal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                } else if (statisticsPage == 2) {
                    val summary = current.summary
                    Text("Analiza całej aktywności", style = MaterialTheme.typography.titleLarge)
                    Text("Średnia na dzień: ${summary.averageCalendarDayMs?.let(::duration) ?: "—"}")
                    Text("Średnia na aktywny dzień: ${summary.averageActiveDayMs?.let(::duration) ?: "—"}")
                    Text("Najdłuższa seria dni: ${summary.dayStreak.longest}")
                    Text("Najdłuższa seria tygodni: ${summary.weekStreak.longest}")
                    when {
                        groupingState == null -> Text("Wczytywanie średniej sesji…")
                        groupingState.isFailure -> {
                            Text("Nie udało się odczytać średniej sesji.", color = MaterialTheme.colorScheme.error)
                            TextButton(retryGrouping) { Text("Ponów odczyt sesji") }
                        }
                        else -> Text("Średni czas metronomu na zakończoną sesję: ${grouping?.averageCompletedPlaybackMs?.let(::duration) ?: "—"}")
                    }
                    val activityDays = summary.days.map { DayTotal(it.date.toString(), it.durationMs) }
                    PracticeBarChart(PracticeStats.chart(activityDays, StatisticsPeriod.DAYS_30, summary.today), "min/dzień")
                }
            }
        }
        if (!musicScope && (history == StatisticsHistory.Unavailable || calculation?.isFailure == true || preferenceState?.isFailure == true)) {
            Text("Nie udało się odczytać lub przeliczyć statystyk. To nie oznacza zerowego czasu.", color = MaterialTheme.colorScheme.error)
            TextButton({ historyRetry++ }) { Text("Ponów odczyt statystyk") }
        } else if (!musicScope && analytics == null) Text("Wczytywanie statystyk…")
        if (!musicScope && analytics != null && statisticsPage == 0) {
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
        if (history is StatisticsHistory.Ready && statisticsPage == 1) GroupedHistory(data, sessions, musicScope, groupingState, retryGrouping)
        if (!musicScope && analytics != null && statisticsPage == 2) PracticeAnalysisPanel(analytics)
        if (!musicScope && history is StatisticsHistory.Ready && sessions.isEmpty()) Text("Rozpocznij ćwiczenie, aby zapisać pierwszą sesję.")
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Dane", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ export.launch("OpenMetronome-sesje-${LocalDate.now()}.csv") }, enabled = !exporting && !backupWorking) {
                Text(if (exporting) "Zapisywanie…" else "Eksportuj CSV")
            }
            OutlinedButton({ data.prepareBackupDocument(3) }, enabled = backupEnabled) {
                Text(if (backupWorking) "Przetwarzanie…" else "Kopia zapasowa")
            }
            OutlinedButton({ backupImport.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) }, enabled = backupEnabled) {
                Text("Przywróć kopię")
            }
        }
        TextButton({ legacyWarning = 2 }, enabled = backupEnabled) { Text("Kopia zgodna z 1.1–1.2 (v2)") }
        TextButton({ legacyWarning = 1 }, enabled = backupEnabled) { Text("Kopia zgodna z 1.0 (v1)") }
        if (!backupEnabled && !backupWorking && !recoveryRequired) Text("Zatrzymaj metronom i poczekaj na zakończenie zapisu oraz innych operacji, aby użyć kopii zapasowej.")
        notice?.let { Text(it) }; backupNotice?.let { Text(it) }
    }
    if (thresholdsOpen && preferences != null) StatisticsThresholdDialog(preferences, { thresholdsOpen = false }, data::saveStatisticsPreferences)
    if (legacyWarning != 0) {
        val version = legacyWarning
        AlertDialog(onDismissRequest = { legacyWarning = 0 }, title = { Text("Kopia kompatybilna v$version") },
            text = {
                Text(if (version == 1)
                    "Format v1 pomija aktywności muzyczne, grupowanie, przebieg konfiguracji, progi i ustawienia ekranu. Aplikacja 1.0 ignoruje procenty i odliczanie. Nowe metra i nowe brzmienia mogą zablokować eksport v1. Pełne dane zachowuje kopia v3."
                else "Kopia v2 zachowuje odtworzenia metronomu. Aktywności, ich ustawienia i korekty grupowania zachowuje pełna kopia v3.")
            },
            confirmButton = { TextButton({
                legacyWarning = 0
                data.prepareBackupDocument(version)
            }) { Text("Eksportuj v$version z pominięciami") } },
            dismissButton = { TextButton({ legacyWarning = 0 }) { Text("Anuluj") } })
    }
    pendingImportPath?.let { path ->
        val uri = path.toUri()
        AlertDialog(onDismissRequest = { pendingImportPath = null }, title = { Text("Przywrócić kopię?") },
            text = { Text("Przywrócenie zastąpi obecne dane. Kopie v1/v2 pomijają aktywności i korekty grupowania. Najpierw warto zapisać obecną kopię v3.") },
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
        GroupingSettings(data)
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
