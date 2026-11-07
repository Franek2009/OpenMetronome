package org.openmetronome.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.*
import org.openmetronome.data.*

@Composable
internal fun StatisticsThresholdDialog(value: StatisticsPreferences, close: () -> Unit, apply: (StatisticsPreferences) -> Unit) {
    var session by rememberSaveable { mutableStateOf(value.minimumSessionSeconds.toString()) }
    var day by rememberSaveable { mutableStateOf(value.activeDayMinutes.toString()) }
    val seconds = session.toIntOrNull()?.takeIf { it in 0..3600 }
    val minutes = day.toIntOrNull()?.takeIf { it in 0..1440 }
    AlertDialog(onDismissRequest = close, title = { Text("Progi aktywności") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Progi przeliczają statystyki także dla starej historii. Żadna sesja nie zostanie usunięta.")
            OutlinedTextField(session, { if (it.length <= 4 && it.all(Char::isDigit)) session = it },
                label = { Text("Minimum sesji, sekundy (0–3600)") }, isError = seconds == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(day, { if (it.length <= 4 && it.all(Char::isDigit)) day = it },
                label = { Text("Aktywny dzień, minuty (0–1440)") }, isError = minutes == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
    }, confirmButton = { TextButton({ if (seconds != null && minutes != null) { apply(StatisticsPreferences(seconds, minutes)); close() } },
        enabled = seconds != null && minutes != null) { Text("Zastosuj progi") } },
        dismissButton = { TextButton(close) { Text("Anuluj") } })
}

@Composable
internal fun StatisticsHistoryPanel(sessions: List<PracticeSession>, activeDates: Set<LocalDate>, today: LocalDate,
    loadTimeline: suspend (PracticeSession) -> String? = { it.timeline }) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    val month = YearMonth.parse(monthText)
    var dateText by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf(0) }
    var selected by remember { mutableStateOf<PracticeSession?>(null) }
    val filtered = remember(sessions, dateText, status) { sessions.filter { session ->
        val dateMatches = dateText?.let { text ->
            val date = LocalDate.parse(text); val zone = ZoneId.of(session.zoneId)
            val first = Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalDate()
            val last = Instant.ofEpochMilli(Math.addExact(session.startedAt, (session.durationMs - 1).coerceAtLeast(0))).atZone(zone).toLocalDate()
            date in first..last
        } ?: true
        dateMatches && when (status) { 1 -> session.finished && !session.interrupted; 2 -> session.interrupted; else -> true }
    } }
    var shown by remember(dateText, status, sessions) { mutableIntStateOf(50) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton({ monthText = month.minusMonths(1).toString() }) { Text("←") }
        Text(monthText, style = MaterialTheme.typography.titleLarge)
        TextButton({ monthText = month.plusMonths(1).toString() }) { Text("→") }
    }
    Text("Kalendarz: • oznacza aktywny dzień według progów. Daty sesji zachowują ich zapisaną strefę.", style = MaterialTheme.typography.bodySmall)
    val leading = month.atDay(1).dayOfWeek.value - 1
    Row(Modifier.fillMaxWidth()) {
        listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd").forEach { Text(it, Modifier.weight(1f)) }
    }
    val cells = ((leading + month.lengthOfMonth() + 6) / 7) * 7
    repeat(cells / 7) { week ->
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { column ->
                val number = week * 7 + column - leading + 1
                if (number !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f))
                else {
                    val date = month.atDay(number)
                    TextButton({ dateText = if (dateText == date.toString()) null else date.toString() },
                        Modifier.weight(1f).sizeIn(minHeight = 48.dp).semantics { contentDescription = "Historia $date" },
                        contentPadding = PaddingValues(0.dp)) {
                        Text("$number${if (date in activeDates) "•" else ""}",
                            color = if (dateText == date.toString()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
    dateText?.let { TextButton({ dateText = null }) { Text("$it · pokaż wszystkie daty") } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Wszystkie sesje", "Zakończone", "Przerwane").forEachIndexed { index, label ->
            FilterChip(status == index, { status = index }, label = { Text(label) })
        }
    }
    Text("${filtered.size} sesji · krótkie również pozostają w historii")
    filtered.take(shown).forEach { session ->
        Card(Modifier.fillMaxWidth().clickable { selected = session }) {
            Column(Modifier.padding(12.dp)) {
                Text(Instant.ofEpochMilli(session.startedAt).atZone(ZoneId.of(session.zoneId)).toString(), style = MaterialTheme.typography.titleSmall)
                Text(dailyPracticeDuration(session.durationMs))
                Text(if (session.interrupted) "Przerwana" else if (session.finished) "Zakończona" else "W toku")
            }
        }
    }
    if (filtered.size > shown) TextButton({ shown += 50 }) { Text("Pokaż kolejne 50 sesji") }
    if (filtered.isEmpty()) Text("Brak sesji dla tych filtrów.")
    selected?.let { session ->
        val config = remember(session) { runCatching { ConfigCodec.decode(session.config) }.getOrNull() }
        val result by produceState<Result<SessionTimeline?>?>(null, session) {
            value = try { Result.success(loadTimeline(session)?.let { SessionTimelineCodec.decode(it, session.durationMs) }) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
        }
        val timeline = result?.getOrNull()
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Szczegóły sesji") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dailyPracticeDuration(session.durationMs)); Text("Strefa: ${session.zoneId}")
                config?.let { Text("Ustawienia początkowe: ${it.effectiveBpmLabel} BPM · baza ${it.tempo.bpm} × ${it.tempoPercent.percent}% · ${it.timeSignature.numerator}/${it.timeSignature.denominator}") }
                Text(when { result == null -> "Odczytywanie przebiegu…"; result?.isFailure == true -> "Nie udało się odczytać przebiegu."
                    timeline?.complete == true -> "Pełny zapis zmian konfiguracji"
                    else -> "Brak pełnego przebiegu: starsza sesja lub niepełny zapis. Nie odtwarzamy brakujących danych." })
                timeline?.changes?.take(50)?.forEach { change ->
                    val item = ConfigCodec.decode(change.config)
                    Text("${dailyPracticeDuration(change.offsetMs)} → ${item.effectiveBpmLabel} BPM · ${item.timeSignature.numerator}/${item.timeSignature.denominator}")
                }
                if ((timeline?.changes?.size ?: 0) > 50) Text("Pokazano pierwsze 50 zmian. Pełne dane zawiera kopia v2.")
            }
        }, confirmButton = { TextButton({ selected = null }) { Text("Zamknij szczegóły") } })
    }
}

@Composable
internal fun PracticeAnalysisPanel(value: PracticeAnalytics) {
    Text("Analiza zaliczonych sesji", style = MaterialTheme.typography.titleLarge)
    Text("Znany przebieg: ${dailyPracticeDuration(value.knownMs)}")
    Text("Bez pełnego przebiegu: ${dailyPracticeDuration(value.unknownMs)}. Brak danych nie jest zerem.")
    if (value.knownMs == 0L) { Text("Szczegółowa analiza pojawi się po zapisaniu nowych sesji."); return }
    Text("Tempo wynikowe", style = MaterialTheme.typography.titleMedium)
    value.tempoMs.forEach { (label, ms) -> Text("$label: ${dailyPracticeDuration(ms)}") }
    Text("Metrum", style = MaterialTheme.typography.titleMedium)
    value.meterMs.forEach { (label, ms) -> Text("$label: ${dailyPracticeDuration(ms)}") }
    Text("Z włączonym treningiem ciszy: ${dailyPracticeDuration(value.trainingMs)}")
    Text("Z włączoną rampą: ${dailyPracticeDuration(value.rampMs)}")
    Text("To czas korzystania z konfiguracji treningu; nie pomiar faktycznej ciszy, jakości gry ani fizycznej precyzji audio.", style = MaterialTheme.typography.bodySmall)
}
