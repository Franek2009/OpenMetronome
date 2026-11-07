package org.openmetronome.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openmetronome.audio.MetronomeState

/** Pure presentation of the existing session. Enter/exit never sends engine commands. */
@Composable
internal fun FullscreenPractice(state: MetronomeState, onStart: () -> Unit, onStop: () -> Unit,
    onExit: () -> Unit, startEnabled: Boolean, reduceMotion: Boolean) {
    var locked by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (locked) locked = false else onExit() }
    val config = state.audio.activeConfig.takeIf { state.keepsScreenOn } ?: state.config
    val pulse = state.audio.pulse.takeIf { state.keepsScreenOn }
    Scaffold(bottomBar = {
        // Stop must remain operable while locked. A locked stopped screen cannot start.
        PlaybackControls(state, onStart, onStop, startEnabled && !locked)
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).safeDrawingPadding()) {
            val bpmSize = minOf(maxWidth.value * .38f, maxHeight.value * .27f, 180f).coerceAtLeast(40f).sp
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onExit, enabled = !locked) { Text("Wróć") }
                    TextButton({ locked = !locked }) { Text(if (locked) "Odblokuj" else "Zablokuj dotyk") }
                }
                Text(config.effectiveBpmLabel,
                    fontSize = bpmSize,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.semantics { contentDescription = "Tempo pełnego ekranu: ${config.effectiveBpmLabel} BPM" })
                Text("BPM · baza ${config.tempo.bpm} × ${config.tempoPercent.percent}%",
                    style = MaterialTheme.typography.titleMedium)
                Text(if (pulse == null) "Gotowy · ${config.timeSignature.numerator}/${config.timeSignature.denominator}"
                    else "Puls ${pulse.beat + 1} / ${config.timeSignature.pulsesPerBar} · takt ${pulse.bar + 1}",
                    style = MaterialTheme.typography.headlineSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(config.timeSignature.pulsesPerBar) { beat ->
                        val lit = config.visualPulse && !reduceMotion && pulse?.beat == beat
                        Box(Modifier.size(32.dp).background(
                            if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape).semantics { contentDescription = "Puls pełnego ekranu ${beat + 1}${if (lit) ": teraz" else ""}" })
                    }
                }
                if (pulse?.silent == true) Text("Cisza treningowa / wyciszony puls")
                if (locked) Text("Dotyk zablokowany · Stop pozostaje dostępny")
            }
        }
    }
}
