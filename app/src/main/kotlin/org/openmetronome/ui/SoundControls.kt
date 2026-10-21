package org.openmetronome.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.openmetronome.R
import org.openmetronome.domain.model.ClickSound
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.data.CustomSampleItem

@Composable
fun SoundControls(config: MetronomeConfig, onChange: (MetronomeConfig) -> Unit, modifier: Modifier = Modifier,
    customSamples: List<CustomSampleItem> = emptyList()) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val selectedName = config.sound.customSampleId?.let { id ->
        customSamples.find { it.id == id }?.let { if (it.available) it.name else "Brak pliku: ${it.name}" } ?: "Brak wybranego sampla"
    } ?: if (config.sound.clickSound in ClickSound.visible) stringResource(config.sound.clickSound.labelResource())
        else "Archiwalne: ${stringResource(config.sound.clickSound.labelResource())}"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sound_title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.app_volume, config.sound.volumePercent))
        val volumeDescription = stringResource(R.string.app_volume_slider)
        Slider(config.sound.volumePercent.toFloat(), { value ->
            onChange(config.copy(sound = config.sound.copy(volumePercent = value.roundToInt().coerceIn(0, 100))))
        }, modifier = Modifier.semantics { contentDescription = volumeDescription }, valueRange = 0f..100f, steps = 99)
        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.sizeIn(minHeight = 48.dp).semantics { contentDescription = "Wybierz brzmienie" }) {
            Text(stringResource(R.string.selected_sound, selectedName))
        }
        Text(stringResource(R.string.sound_hint), style = MaterialTheme.typography.bodySmall)
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Brzmienie") },
            text = {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).selectableGroup()) {
                        item {
                            Text("Wybór nie uruchamia dźwięku. Zmiana podczas grania nastąpi na granicy taktu.",
                                Modifier.padding(bottom = 8.dp))
                        }
                        items(ClickSound.visible, key = { it.name }) { sound ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                selected = config.sound.customSampleId == null && config.sound.clickSound == sound,
                                role = Role.RadioButton,
                                onClick = {
                                    onChange(config.copy(sound = config.sound.copy(clickSound = sound, customSampleId = null)))
                                    showPicker = false
                                },
                            ), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = config.sound.customSampleId == null && config.sound.clickSound == sound, onClick = null)
                                Text(stringResource(sound.labelResource()), Modifier.padding(start = 8.dp))
                            }
                        }
                        item { Text("Moje sample", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                        if (customSamples.isEmpty()) item { Text("Użyj przycisku Importuj sample w ustawieniach.") }
                        items(customSamples, key = { it.id }) { sample ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                selected = config.sound.customSampleId == sample.id, enabled = sample.available, role = Role.RadioButton,
                                onClick = {
                                    onChange(config.copy(sound = config.sound.copy(clickSound = ClickSound.IMPULSE_FULL, customSampleId = sample.id)))
                                    showPicker = false
                                }), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(config.sound.customSampleId == sample.id, null, enabled = sample.available)
                                Text(if (sample.available) sample.name else "Brak pliku: ${sample.name}", Modifier.padding(start = 8.dp))
                            }
                        }
                    }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.sound_close)) } },
        )
    }
}

internal fun ClickSound.labelResource(): Int = when (this) {
    ClickSound.CLASSIC -> R.string.sound_classic
    ClickSound.WOOD -> R.string.sound_wood
    ClickSound.SHORT -> R.string.sound_short
    ClickSound.WOODBLOCK -> R.string.sound_woodblock
    ClickSound.RIMSHOT -> R.string.sound_rimshot
    ClickSound.COWBELL -> R.string.sound_cowbell
    ClickSound.CLAP -> R.string.sound_clap
    ClickSound.PULSE -> R.string.sound_pulse
    ClickSound.ELECTRO_LOW -> R.string.sound_electro_low
    ClickSound.ELECTRO_MID -> R.string.sound_electro_mid
    ClickSound.ELECTRO_HIGH -> R.string.sound_electro_high
    ClickSound.ELECTRO_DUAL -> R.string.sound_electro_dual
    ClickSound.IMPULSE_SHORT -> R.string.sound_impulse_short
    ClickSound.IMPULSE_FULL -> R.string.sound_curated_full
    ClickSound.IMPULSE_BRIGHT -> R.string.sound_impulse_bright
    ClickSound.IMPULSE_ROUGH -> R.string.sound_impulse_rough
    ClickSound.WOOD_DRY -> R.string.sound_wood_dry
    ClickSound.CLAVES -> R.string.sound_claves
    ClickSound.WOOD_RESONANT -> R.string.sound_wood_resonant
    ClickSound.WOOD_HARD -> R.string.sound_wood_hard
    ClickSound.METAL_BELL -> R.string.sound_metal_bell
    ClickSound.METAL_ANVIL -> R.string.sound_metal_anvil
    ClickSound.METAL_PING -> R.string.sound_metal_ping
    ClickSound.METAL_DRY -> R.string.sound_metal_dry
    ClickSound.NOISE_CRACK -> R.string.sound_noise_crack
    ClickSound.NOISE_BRUSH -> R.string.sound_noise_brush
    ClickSound.NOISE_RIM -> R.string.sound_noise_rim
    ClickSound.NOISE_WIDE -> R.string.sound_noise_wide
    ClickSound.FULL_LOW_750 -> R.string.sound_curated_low
    ClickSound.FULL_LOW_900 -> R.string.sound_full_low_900
    ClickSound.FULL_HIGH_1200 -> R.string.sound_curated_bright
    ClickSound.FULL_HIGH_1400 -> R.string.sound_full_high_1400
    ClickSound.FULL_HIGH_1650 -> R.string.sound_curated_high
    ClickSound.FULL_SHORT -> R.string.sound_curated_short
    ClickSound.FULL_TIGHT -> R.string.sound_full_tight
    ClickSound.FULL_LONG -> R.string.sound_full_long
    ClickSound.FULL_HELD -> R.string.sound_curated_long
    ClickSound.FULL_SOFT -> R.string.sound_full_soft
    ClickSound.FULL_BRIGHT -> R.string.sound_full_bright
    ClickSound.FULL_ROUND -> R.string.sound_full_round
    ClickSound.FULL_DENSE -> R.string.sound_full_dense
    ClickSound.FULL_LOW_DENSE -> R.string.sound_full_low_dense
    ClickSound.FULL_HIGH_TIGHT -> R.string.sound_full_high_tight
}
