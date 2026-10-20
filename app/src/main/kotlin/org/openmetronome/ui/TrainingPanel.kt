package org.openmetronome.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.Subdivision
import org.openmetronome.domain.model.RhythmConfig
import org.openmetronome.domain.model.ClickAccent

@Composable
fun TrainingPanel(config: MetronomeConfig, onChange: (MetronomeConfig) -> Unit, initiallyExpanded: Boolean = false) {
    var expanded by rememberSaveable(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("training-toggle")) {
        Text(if (expanded) "Zwiń trening" else "Trening pulsu", style = MaterialTheme.typography.titleMedium)
    }
    if (!expanded) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val silence = config.silence
        Text("Losowe wyciszenia: ${silence.probability}%")
        Slider(silence.probability.toFloat(), { onChange(config.copy(silence = silence.copy(probability = probabilityFromSlider(it)))) },
            modifier = Modifier.semantics { contentDescription = "Prawdopodobieństwo wyciszenia" },
            valueRange = 0f..100f, steps = 99)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton({ onChange(config.copy(silence = silence.copy(probability = silence.probability - 1))) },
                enabled = silence.probability > 0,
                modifier = Modifier.sizeIn(minHeight = 48.dp).semantics { contentDescription = "Zmniejsz wyciszenia o 1 punkt procentowy" }) { Text("−1%") }
            OutlinedButton({ onChange(config.copy(silence = silence.copy(probability = silence.probability + 1))) },
                enabled = silence.probability < 100,
                modifier = Modifier.sizeIn(minHeight = 48.dp).semantics { contentDescription = "Zwiększ wyciszenia o 1 punkt procentowy" }) { Text("+1%") }
        }
        Row {
            Text("Wyciszanie całych taktów", Modifier.weight(1f))
            Switch(silence.cycleEnabled, { onChange(config.copy(silence = silence.copy(cycleEnabled = it))) },
                Modifier.semantics { contentDescription = "Wyciszanie całych taktów" })
        }
        if (silence.cycleEnabled) {
            NumberSetting("Takty słyszalne", silence.audibleBars, 1..64) {
                onChange(config.copy(silence = silence.copy(audibleBars = it)))
            }
            NumberSetting("Takty ciszy", silence.silentBars, 1..64) {
                onChange(config.copy(silence = silence.copy(silentBars = it)))
            }
        }
        Row {
            Text("Progresja wyciszeń", Modifier.weight(1f))
            Switch(config.progression.enabled, { onChange(config.copy(progression = config.progression.copy(enabled = it))) },
                Modifier.semantics { contentDescription = "Progresja wyciszeń" })
        }
        if (config.progression.enabled) {
            NumberSetting("Wzrost o p.p.", config.progression.increment, 1..100) { onChange(config.copy(progression = config.progression.copy(increment = it))) }
            NumberSetting("Co ile sekund", config.progression.intervalSeconds, 1..3600) { onChange(config.copy(progression = config.progression.copy(intervalSeconds = it))) }
        }
        Row {
            Text("Automatyczne tempo", Modifier.weight(1f))
            Switch(config.ramp.enabled, { onChange(config.copy(ramp = config.ramp.copy(enabled = it))) },
                Modifier.semantics { contentDescription = "Automatyczne tempo" })
        }
        if (config.ramp.enabled) {
            NumberSetting("Tempo początkowe", config.ramp.startBpm, 20..300) { onChange(config.copy(ramp = config.ramp.copy(startBpm = it))) }
            NumberSetting("Tempo docelowe", config.ramp.targetBpm, 20..300) { onChange(config.copy(ramp = config.ramp.copy(targetBpm = it))) }
            NumberSetting("Krok BPM", config.ramp.step, 1..280) { onChange(config.copy(ramp = config.ramp.copy(step = it))) }
            NumberSetting("Co ile taktów", config.ramp.barsPerStep, 1..64) { onChange(config.copy(ramp = config.ramp.copy(barsPerStep = it))) }
        }
    }
}

@Composable
fun RhythmControls(config: MetronomeConfig, onChange: (MetronomeConfig) -> Unit, large: Boolean = false) {
    Text("Podział głównego pulsu", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Subdivision.entries.forEach { subdivision ->
            FilterChip(config.rhythm.subdivision == subdivision, { onChange(config.copy(rhythm = RhythmConfig(subdivision))) },
                modifier = Modifier.sizeIn(minHeight = if (large) 56.dp else 48.dp), label = {
                    Text(subdivision.label, style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelLarge)
                })
        }
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton({ expanded = !expanded }) { Text(if (expanded) "Zwiń akcenty podziałów" else "Akcenty podziałów") }
    if (expanded) {
        config.rhythm.subdivision.offsets.indices.forEach { slot ->
            val accent = config.rhythm.accentAt(slot)
            TextButton({
                val accents = config.rhythm.subdivision.offsets.indices.map { config.rhythm.accentAt(it) }.toMutableList()
                accents[slot] = ClickAccent.entries[(accent.ordinal + 1) % ClickAccent.entries.size]
                onChange(config.copy(rhythm = config.rhythm.copy(accents = accents)))
            }) { Text("Podział ${slot + 1}: ${when (accent) { ClickAccent.NORMAL -> "zwykły"; ClickAccent.ACCENT -> "akcent"; ClickAccent.MUTE -> "cisza" }}") }
        }
    }
}

@Composable
fun NumberSetting(label: String, value: Int, range: IntRange, change: (Int) -> Unit) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    Row(Modifier.fillMaxWidth()) {
        OutlinedTextField(draft, { text ->
            if (text.length <= 4 && text.all(Char::isDigit)) {
                draft = text
                text.toIntOrNull()?.takeIf { it in range }?.let(change)
            }
        }, label = { Text(label) }, modifier = Modifier.weight(1f), singleLine = true,
            isError = draft.toIntOrNull()?.let { it !in range } ?: true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        TextButton({ change(value - 1) }, enabled = value > range.first,
            modifier = Modifier.semantics { contentDescription = "Zmniejsz: $label" }) { Text("−") }
        TextButton({ change(value + 1) }, enabled = value < range.last,
            modifier = Modifier.semantics { contentDescription = "Zwiększ: $label" }) { Text("+") }
    }
}
