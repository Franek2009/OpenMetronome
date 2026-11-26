package org.openmetronome.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.openmetronome.data.*
import org.openmetronome.domain.model.*

private fun MusicActivityKind.label() = when (this) {
    MusicActivityKind.LESSON -> "Lekcja"
    MusicActivityKind.INDIVIDUAL_PRACTICE -> "Ćwiczenie indywidualne"
    MusicActivityKind.ENSEMBLE_REHEARSAL -> "Próba zespołowa"
    MusicActivityKind.CONCERT -> "Koncert"
    MusicActivityKind.OTHER -> "Inne"
}

@Composable
internal fun MusicActivityHistory(data: AppData) {
    var retry by remember { mutableIntStateOf(0) }
    val source = remember(data, retry) {
        data.musicActivities.map<List<MusicActivity>, Result<List<MusicActivity>>> { Result.success(it) }
            .catch { error -> if (error is kotlinx.coroutines.CancellationException) throw error else emit(Result.failure(error)) }
    }
    val result by source.collectAsStateWithLifecycle(initialValue = null)
    val activities = result?.getOrNull().orEmpty()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = activities.firstOrNull { it.id == editingId }
    OutlinedButton({ adding = true }) { Text("+ Dodaj aktywność") }
    when {
        result == null -> Text("Wczytywanie aktywności…")
        result?.isFailure == true -> TextButton({ retry++ }) { Text("Ponów odczyt aktywności") }
        activities.isEmpty() -> Text("Brak aktywności muzycznych.")
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
      items(activities, key = { it.id }) { activity ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(activity.details.kind.label(), style = MaterialTheme.typography.titleMedium)
                Text(Instant.ofEpochMilli(activity.details.startAt).atZone(ZoneId.of(activity.details.zoneId)).toLocalDateTime().toString())
                Text(dailyPracticeDuration(activity.details.timerDurationMs))
                activity.details.instrument?.let { Text(it) }
                activity.details.note?.let { Text(it) }
                Row {
                    if (activity.details.source == MusicActivitySource.MANUAL && activity.details.intervals.size == 1 && activity.details.timerDurationMs % 1000L == 0L && activity.details.startAt % 1000L == 0L) TextButton({ editingId = activity.id }) { Text("Edytuj") }
                    TextButton({ deletingId = activity.id }) { Text("Usuń") }
                }
            }
        }
      }
    }
    if (adding || editing != null) ActivityDialog(editing, { adding = false; editingId = null }) { details ->
        data.saveMusicActivity(details, editing?.id)
        adding = false; editingId = null
    }
    deletingId?.let { id -> AlertDialog(onDismissRequest = { deletingId = null }, title = { Text("Usunąć aktywność?") },
        confirmButton = { TextButton({ data.deleteMusicActivity(id); deletingId = null }) { Text("Usuń") } },
        dismissButton = { TextButton({ deletingId = null }) { Text("Anuluj") } }) }
}

@Composable
private fun ActivityDialog(activity: MusicActivity?, close: () -> Unit, save: suspend (MusicActivityDetails) -> Unit) {
    val initial = activity?.details
    val zone = initial?.zoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()
    val start = initial?.startAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() } ?: LocalDateTime.now().minusMinutes(30).withSecond(0).withNano(0)
    var kind by rememberSaveable(activity?.id) { mutableStateOf((initial?.kind ?: MusicActivityKind.INDIVIDUAL_PRACTICE).name) }
    var date by rememberSaveable(activity?.id) { mutableStateOf(start.toLocalDate().toString()) }
    var time by rememberSaveable(activity?.id) { mutableStateOf(start.toLocalTime().withNano(0).toString()) }
    var hours by rememberSaveable(activity?.id) { mutableStateOf(((initial?.timerDurationMs ?: 1_800_000) / 3_600_000).toString()) }
    var minutes by rememberSaveable(activity?.id) { mutableStateOf((((initial?.timerDurationMs ?: 1_800_000) / 60_000) % 60).toString()) }
    val remainder = (initial?.timerDurationMs ?: 0L) % 60_000L
    var instrument by rememberSaveable(activity?.id) { mutableStateOf(initial?.instrument.orEmpty()) }
    var note by rememberSaveable(activity?.id) { mutableStateOf(initial?.note.orEmpty()) }
    val details = runCatching {
        val local = LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time))
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.size == 1)
        val from = local.toInstant(offsets.single()).toEpochMilli()
        val hourValue = hours.toLong().also { require(it in 0..168) }
        val minuteValue = minutes.toLong().also { require(it in 0..59) }
        val duration = (hourValue * 60 + minuteValue) * 60_000 + remainder
        require(duration in 1..604_800_000)
        MusicActivityDetails.manual(MusicActivityKind.valueOf(kind), from, Math.addExact(from, duration), zone.id, instrument, note)
    }.getOrNull()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text(if (activity == null) "Dodaj aktywność" else "Edytuj aktywność") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MusicActivityKind.entries.forEach { item -> FilterChip(kind == item.name, { kind = item.name }, label = { Text(item.label()) }) }
            }
            OutlinedTextField(date, { date = it }, label = { Text("Data RRRR-MM-DD") }, singleLine = true)
            OutlinedTextField(time, { time = it }, label = { Text("Godzina GG:MM") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hours, { hours = it }, Modifier.weight(1f), label = { Text("Godziny") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                OutlinedTextField(minutes, { minutes = it }, Modifier.weight(1f), label = { Text("Minuty") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 45, 60).forEach { amount -> TextButton({ hours = (amount / 60).toString(); minutes = (amount % 60).toString() }) { Text("$amount min") } }
            }
            Row {
                TextButton({ android.app.DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d).toString() },
                    runCatching { LocalDate.parse(date).year }.getOrDefault(start.year), runCatching { LocalDate.parse(date).monthValue - 1 }.getOrDefault(start.monthValue - 1),
                    runCatching { LocalDate.parse(date).dayOfMonth }.getOrDefault(start.dayOfMonth)).show() }) { Text("Wybierz datę") }
                TextButton({ val selected = runCatching { LocalTime.parse(time) }.getOrDefault(start.toLocalTime())
                    android.app.TimePickerDialog(context, { _, h, m -> time = LocalTime.of(h, m).toString() }, selected.hour, selected.minute, true).show() }) { Text("Wybierz godzinę") }
            }
            OutlinedTextField(instrument, { instrument = it }, label = { Text("Instrument (opcjonalnie)") })
            OutlinedTextField(note, { note = it }, label = { Text("Notatka (opcjonalnie)") })
            if (details == null) Text("Sprawdź datę, godzinę i czas. Godzina musi istnieć jednoznacznie w strefie $zone.", color = MaterialTheme.colorScheme.error)
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({ details?.let { value -> scope.launch {
        saving = true
        try { save(value) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { saveError = "Nie udało się zapisać aktywności. Spróbuj ponownie." }
        finally { saving = false }
    } } }, enabled = details != null && !saving) { Text(if (saving) "Zapisywanie…" else "Zapisz") } },
        dismissButton = { TextButton(close) { Text("Anuluj") } })
}

@Composable
internal fun GroupingSettings(data: AppData) {
    val gap by data.settings.groupingGap.collectAsStateWithLifecycle(initialValue = PracticeGroupingGap.DEFAULT)
    val music by data.settings.musicStatistics.collectAsStateWithLifecycle(initialValue = null)
    var open by rememberSaveable { mutableStateOf(false) }
    var reset by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<PracticeGroupingPreview?>(null) }
    val scope = rememberCoroutineScope()
    Text("Grupowanie sesji", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PracticeGroupingGap.entries.forEach { value -> FilterChip(value == gap, { data.savePracticeGroupingGap(value) },
            label = { Text(value.minutes?.let { "$it min" } ?: "Wyłączone") }) }
    }
    TextButton({ open = true }) { Text("Ustawienia aktywności muzycznej") }
    TextButton({ scope.launch { runCatching { data.previewPracticeGrouping(PracticeGroupingChange.ResetAll, Instant.now()) }
        .onSuccess { preview = it; reset = true }.onFailure { data.error.value = "Nie udało się przygotować podglądu." } } }) { Text("Resetuj korekty grupowania") }
    if (open) MusicSettingsDialog(music ?: MusicStatisticsPreferences(), { open = false }) { data.saveMusicStatisticsPreferences(it); open = false }
    if (reset && preview != null) GroupingPreviewDialog(preview!!, { reset = false }, { data.applyPracticeGrouping(preview!!); reset = false })
}

@Composable
private fun MusicSettingsDialog(value: MusicStatisticsPreferences, close: () -> Unit, save: (MusicStatisticsPreferences) -> Unit) {
    var zone by rememberSaveable { mutableStateOf(value.zone.id) }
    var day by rememberSaveable { mutableStateOf(value.activeDayMinutes.toString()) }
    var goal by rememberSaveable { mutableStateOf((value.weeklyGoalDays ?: 5).toString()) }
    var enabled by rememberSaveable { mutableStateOf(value.weeklyGoalDays != null) }
    val result = runCatching { MusicStatisticsPreferences(ZoneId.of(zone), day.toInt(), if (enabled) goal.toInt() else null) }.getOrNull()
    AlertDialog(onDismissRequest = close, title = { Text("Statystyki muzyczne") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(day, { day = it }, label = { Text("Aktywny dzień, minuty") })
            OutlinedTextField(zone, { zone = it }, label = { Text("Strefa czasowa, np. Europe/Warsaw") })
            Row { Text("Cel tygodniowy"); Switch(enabled, { enabled = it }) }
            if (enabled) OutlinedTextField(goal, { goal = it }, label = { Text("Aktywne dni (1–7)") })
            if (result == null) Text("Sprawdź ustawienia.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton({ result?.let(save) }, enabled = result != null) { Text("Zapisz") } },
        dismissButton = { TextButton(close) { Text("Anuluj") } })
}

@Composable
internal fun GroupingPreviewDialog(preview: PracticeGroupingPreview, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("Potwierdź zmianę grupowania") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Przed")
            preview.before.forEach { Text("${it.playbackIds.size} odtw. · ${dailyPracticeDuration(it.playbackMs)} · ${Instant.ofEpochMilli(it.startedAt)}") }
            Text("Po")
            preview.after.forEach { Text("${it.playbackIds.size} odtw. · ${dailyPracticeDuration(it.playbackMs)} · ${Instant.ofEpochMilli(it.startedAt)}") }
            if (preview.resetCount > 0) Text("Resetowane korekty: ${preview.resetCount}")
        }
    }, confirmButton = { TextButton(confirm) { Text("Zastosuj") } }, dismissButton = { TextButton(close) { Text("Anuluj") } })
}

@Composable
internal fun GroupedHistory(data: AppData, sessions: List<PracticeSession>, includeMusic: Boolean) {
    var revision by remember { mutableIntStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<PracticeGroupingPreview?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val gap by data.settings.groupingGap.collectAsStateWithLifecycle(initialValue = PracticeGroupingGap.DEFAULT)
    val scope = rememberCoroutineScope()
    val grouping by produceState<Result<PracticeGroupingSummary>?>(null, sessions, gap, revision) {
        value = runCatching { data.practiceGrouping(Instant.now()) }
    }
    fun change(action: PracticeGroupingChange) {
        scope.launch {
            runCatching { data.previewPracticeGrouping(action, Instant.now()) }
                .onSuccess { preview = it }.onFailure { data.error.value = "Nie udało się przygotować zmiany grupowania." }
        }
    }
    if (grouping == null) Text("Wczytywanie sesji…")
    if (grouping?.isFailure == true) TextButton({ revision++ }) { Text("Ponów odczyt sesji") }
    if (includeMusic) MusicActivityHistory(data)
    val groups = grouping?.getOrNull()?.groups.orEmpty()
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
      items(groups.size, key = { groups[it].playbackIds.first() }) { reverseIndex ->
        val index = groups.lastIndex - reverseIndex
        val group = groups[index]
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                TextButton({ expanded = if (expanded == group.playbackIds.first()) null else group.playbackIds.first() }) {
                    Text("${Instant.ofEpochMilli(group.startedAt).atZone(ZoneId.systemDefault()).toLocalDateTime()} · ${group.playbackIds.size} odtw.")
                }
                Text("Odtwarzanie: ${dailyPracticeDuration(group.playbackMs)} · rozpiętość: ${dailyPracticeDuration(group.spanMs)}")
                if (expanded == group.playbackIds.first()) {
                    group.playbackIds.forEachIndexed { i, id ->
                        sessions.firstOrNull { it.id == id }?.let { session ->
                            TextButton({ selectedId = session.id }) {
                                Text("${Instant.ofEpochMilli(session.startedAt).atZone(ZoneId.of(session.zoneId)).toLocalDateTime()} · ${dailyPracticeDuration(session.durationMs)}")
                            }
                            if (i > 0) TextButton({ change(PracticeGroupingChange.SplitBefore(id)) }) { Text("Rozdziel przed odtworzeniem") }
                        }
                    }
                    Row {
                        if (index > 0) TextButton({ change(PracticeGroupingChange.Join(groups[index - 1].playbackIds.last(), group.playbackIds.first())) }) { Text("Połącz z poprzednią") }
                        TextButton({ change(PracticeGroupingChange.ResetGroup(group.playbackIds.first())) }) { Text("Resetuj") }
                    }
                }
            }
        }
      }
    }
    if (groups.isEmpty() && grouping?.isSuccess == true) Text("Brak odtworzeń.")
    sessions.firstOrNull { it.id == selectedId }?.let { session ->
        val config = remember(session.config) { runCatching { ConfigCodec.decode(session.config) }.getOrNull() }
        val timeline by produceState<Result<SessionTimeline?>?>(null, session) {
            value = try { Result.success(data.sessionTimeline(session)?.let { SessionTimelineCodec.decode(it, session.durationMs) }) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
        }
        AlertDialog(onDismissRequest = { selectedId = null }, title = { Text("Szczegóły odtworzenia") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(dailyPracticeDuration(session.durationMs))
                Text("Strefa: ${session.zoneId}")
                config?.let { Text("${it.effectiveBpmLabel} BPM · ${it.timeSignature.numerator}/${it.timeSignature.denominator}") }
                Text(when { timeline == null -> "Wczytywanie przebiegu…"; timeline?.isFailure == true -> "Nie udało się odczytać przebiegu."
                    timeline?.getOrNull()?.complete == true -> "Pełny zapis zmian konfiguracji"; else -> "Brak pełnego przebiegu konfiguracji." })
                timeline?.getOrNull()?.changes?.take(50)?.forEach { change ->
                    val item = ConfigCodec.decode(change.config)
                    Text("${dailyPracticeDuration(change.offsetMs)} · ${item.effectiveBpmLabel} BPM")
                }
            }
        }, confirmButton = { TextButton({ selectedId = null }) { Text("Zamknij") } })
    }
    preview?.let { value -> GroupingPreviewDialog(value, { preview = null }, {
        data.applyPracticeGrouping(value)
        scope.launch { data.awaitWrites(); revision++ }
        preview = null
    }) }
}
