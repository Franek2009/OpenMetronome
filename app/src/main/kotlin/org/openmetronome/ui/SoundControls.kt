package org.openmetronome.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.openmetronome.R
import org.openmetronome.domain.model.ClickSound
import org.openmetronome.domain.model.MetronomeConfig

@Composable
fun SoundControls(config: MetronomeConfig, onChange: (MetronomeConfig) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sound_title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.app_volume, config.sound.volumePercent))
        val volumeDescription = stringResource(R.string.app_volume_slider)
        Slider(config.sound.volumePercent.toFloat(), { value ->
            onChange(config.copy(sound = config.sound.copy(volumePercent = value.roundToInt().coerceIn(0, 100))))
        }, modifier = Modifier.semantics { contentDescription = volumeDescription }, valueRange = 0f..100f, steps = 99)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClickSound.entries.forEach { sound ->
                FilterChip(config.sound.clickSound == sound, {
                    onChange(config.copy(sound = config.sound.copy(clickSound = sound)))
                }, label = { Text(stringResource(when (sound) {
                    ClickSound.CLASSIC -> R.string.sound_classic
                    ClickSound.WOOD -> R.string.sound_wood
                    ClickSound.SHORT -> R.string.sound_short
                })) })
            }
        }
        Text(stringResource(R.string.sound_hint), style = MaterialTheme.typography.bodySmall)
    }
}
