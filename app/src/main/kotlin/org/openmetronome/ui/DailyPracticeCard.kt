package org.openmetronome.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import org.openmetronome.data.*

@Composable
internal fun DailyPracticeCard(data: AppData, today: LocalDate) {
    var retry by remember { mutableIntStateOf(0) }
    val source = remember(data, today, retry) { data.dailyPractice(today) }
    val value by source.collectAsStateWithLifecycle(initialValue = DailyPractice.Loading(today))
    // collectAsState may retain the previous flow's last value until the new flow emits.
    val current = if (value.date == today) value else DailyPractice.Loading(today)
    DailyPracticeSummary(current) { retry++ }
}

@Composable
internal fun DailyPracticeSummary(value: DailyPractice, retry: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Dzisiaj", style = MaterialTheme.typography.titleMedium)
            when (value) {
                is DailyPractice.Loading -> Text("Wczytywanie czasu…")
                is DailyPractice.Unavailable -> {
                    Text("Nie udało się odczytać dzisiejszego czasu.", color = MaterialTheme.colorScheme.error)
                    TextButton(retry) { Text("Ponów odczyt czasu") }
                }
                is DailyPractice.Ready -> Text(dailyPracticeDuration(value.durationMs),
                    modifier = Modifier.semantics { contentDescription = "Dzisiaj ćwiczono: ${dailyPracticeDuration(value.durationMs)}" },
                    style = MaterialTheme.typography.headlineMedium)
            }
            Text("${value.date} · z zapisów sesji, aktualizacja około co 5 s", style = MaterialTheme.typography.bodySmall)
        }
    }
}
