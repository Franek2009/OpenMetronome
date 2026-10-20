package org.openmetronome.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt
import org.openmetronome.R
import org.openmetronome.audio.MetronomeState
import org.openmetronome.domain.model.*

@Composable
fun MetronomeScreen(
    state: MetronomeState,
    onTempoChange: (Int) -> Unit,
    onIncreaseTempo: () -> Unit,
    onDecreaseTempo: () -> Unit,
    onTimeSignatureChange: (TimeSignature) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onConfigChange: (MetronomeConfig) -> Unit = {},
    onTapTempo: () -> Unit = {},
    onResetTapTempo: () -> Unit = {},
    showPlaybackControls: Boolean = true,
    onAdjustTempo: (Int) -> Unit = { onTempoChange((state.config.tempo.bpm + it).coerceIn(20, 300)) },
    onBeatAccent: (Int) -> Unit = { onConfigChange(state.config.cycleBeatAccent(it)) },
) {
    val config = state.config
    // Form-factor classification only, not pixel sizing. Content height changes with IME.
    @SuppressLint("ConfigurationScreenWidthHeight")
    val windowHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    var tempoEditorOpen by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { onResetTapTempo() } }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { if (showPlaybackControls) PlaybackControls(state, onStart, onStop) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // IME changes the available content height, not the window's form factor.
            val layout = InstrumentLayout.choose(maxWidth.value, windowHeightDp)
            val tablet = layout.tablet
            val short = layout == InstrumentLayout.PHONE_WIDE
            val instrument: @Composable ColumnScope.() -> Unit = {
                PulseLamps(state, onBeatAccent, if (tablet) 72.dp else if (short) 32.dp else 44.dp,
                    short || layout == InstrumentLayout.TABLET_LANDSCAPE)
                TempoPanel(config, onTempoChange, onIncreaseTempo, onDecreaseTempo, onAdjustTempo, onTapTempo,
                    large = tablet, short = short, hideHint = layout == InstrumentLayout.TABLET_LANDSCAPE,
                    onEditTempo = { tempoEditorOpen = true })
            }
            val rhythm: @Composable ColumnScope.() -> Unit = {
                if (short) OutlinedButton(onTapTempo, Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
                    Text(stringResource(R.string.tap_tempo))
                }
                Text(stringResource(R.string.time_signature_title), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeSignature.entries.forEach { signature ->
                        val meterDescription = stringResource(R.string.time_signature_accessibility, signature.numerator, signature.denominator)
                        FilterChip(config.timeSignature == signature, { onTimeSignatureChange(signature) },
                            modifier = Modifier.sizeIn(minHeight = if (tablet) 56.dp else 48.dp).semantics {
                                contentDescription = meterDescription
                            }, label = { Text(stringResource(R.string.time_signature_value, signature.numerator, signature.denominator),
                                style = if (tablet) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelLarge) })
                    }
                }
                RhythmControls(config, onConfigChange, large = tablet)
            }
            val training: @Composable ColumnScope.() -> Unit = {
                TrainingPanel(config, onConfigChange, initiallyExpanded = tablet)
                state.audio.activeConfig?.takeIf { state.keepsScreenOn }?.let { active ->
                    Text("Aktualnie: ${active.tempo.bpm} BPM · wyciszenia ${active.silence.probability}%",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.offline_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (layout) {
                InstrumentLayout.TABLET_PORTRAIT -> {
                    Column(Modifier.fillMaxSize().testTag("layout-tablet-portrait").verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        InstrumentSurface(Modifier.testTag("instrument-panel"), prominent = true, content = instrument)
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            InstrumentSurface(Modifier.weight(1f).testTag("rhythm-panel"), content = rhythm)
                            InstrumentSurface(Modifier.weight(1f).testTag("training-panel"), content = training)
                        }
                    }
                }
                InstrumentLayout.TABLET_LANDSCAPE -> {
                    Row(Modifier.fillMaxSize().testTag("layout-tablet-landscape").padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center) {
                            InstrumentSurface(Modifier.testTag("instrument-panel"), prominent = true, content = instrument)
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            InstrumentSurface(Modifier.testTag("rhythm-panel"), content = rhythm)
                            InstrumentSurface(Modifier.testTag("training-panel"), content = training)
                        }
                    }
                }
                InstrumentLayout.PHONE_WIDE -> {
                    Row(Modifier.fillMaxSize().testTag("layout-phone-wide").padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1.1f).fillMaxHeight().testTag("instrument-panel").verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(0.dp), content = instrument)
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            rhythm(); training()
                        }
                    }
                }
                InstrumentLayout.PHONE -> {
                    Column(Modifier.fillMaxSize().testTag("layout-phone").verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.fillMaxWidth().testTag("instrument-panel"), verticalArrangement = Arrangement.spacedBy(10.dp), content = instrument)
                        rhythm(); training()
                    }
                }
            }
        }
    }
    if (tempoEditorOpen) TempoEditorDialog(config.tempo.bpm,
        onConfirm = { onTempoChange(it); tempoEditorOpen = false }, onDismiss = { tempoEditorOpen = false })
}

@Composable
private fun PulseLamps(state: MetronomeState, onCycle: (Int) -> Unit, lampSize: Dp, short: Boolean) {
    val requested = state.config
    val pulse = state.audio.pulse.takeIf { requested.visualPulse && state.keepsScreenOn }
    val activeMeter = state.audio.activeConfig?.timeSignature ?: requested.timeSignature
    if (!short) Text("Puls taktu", style = MaterialTheme.typography.titleMedium)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        repeat(requested.timeSignature.pulsesPerBar) { beat ->
            val accent = requested.beatAccentAt(beat)
            val active = pulse?.beat == beat && beat < activeMeter.pulsesPerBar
            val label = when (accent) { ClickAccent.NORMAL -> "zwykły"; ClickAccent.ACCENT -> "akcent"; ClickAccent.MUTE -> "cisza" }
            val base = when (accent) {
                ClickAccent.NORMAL -> Color(0xFFB3A54B)
                ClickAccent.ACCENT -> Color(0xFFC17B36)
                ClickAccent.MUTE -> Color(0xFF647068)
            }
            Column(Modifier.sizeIn(minWidth = 48.dp, minHeight = if (short) 48.dp else lampSize + 20.dp)
                .clickable(role = Role.Button) { onCycle(beat) }
                .semantics(mergeDescendants = true) {
                    contentDescription = "Puls ${beat + 1}: $label"
                    stateDescription = if (active) "Teraz${if (pulse.silent) ", wyciszony" else ""}" else label
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(lampSize)) {
                    val radius = size.minDimension * .39f
                    drawCircle(Brush.radialGradient(listOf(base, base.copy(alpha = .25f))), radius)
                    drawCircle(if (active) Color(0xFFD8DFD5) else base.copy(alpha = .55f), radius,
                        style = Stroke(if (active) 3.dp.toPx() else 1.dp.toPx()))
                    drawCircle(Color.White.copy(alpha = .18f), radius * .23f, center + Offset(-radius * .3f, -radius * .35f))
                    if (accent == ClickAccent.MUTE) drawLine(Color(0xFFE0E2DB), center + Offset(-radius*.6f, radius*.6f),
                        center + Offset(radius*.6f, -radius*.6f), 2.dp.toPx())
                    if (accent == ClickAccent.ACCENT) drawCircle(Color(0xFFE0E2DB), radius * .23f, style = Stroke(1.dp.toPx()))
                }
                Text("${beat + 1}", style = if (lampSize > 44.dp) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (!short) Text("Dotknij lampki: zwykły → akcent → cisza", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (pulse != null) Text("Takt ${pulse.bar + 1} · puls ${pulse.beat + 1}${if (pulse.silent) " · cisza" else ""}",
        style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun TempoPanel(config: MetronomeConfig, onTempo: (Int) -> Unit, onIncrease: () -> Unit,
    onDecrease: () -> Unit, onAdjust: (Int) -> Unit, onTap: () -> Unit, large: Boolean, short: Boolean,
    hideHint: Boolean, onEditTempo: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val expandedControls = large && maxWidth >= 480.dp
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            TempoStep("−1", stringResource(R.string.decrease_tempo), config.tempo.bpm > 20, expandedControls, onDecrease)
            TempoStep("−5", "Zmniejsz tempo o 5 BPM", config.tempo.bpm > 20, expandedControls) { onAdjust(-5) }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            val description = stringResource(R.string.tempo_value, config.tempo.bpm)
            Text(config.tempo.bpm.toString(), fontSize = if (expandedControls) 128.sp else if (large) 80.sp else if (short) 56.sp else 72.sp, fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 80.dp)
                    .clickable(role = Role.Button, onClick = onEditTempo)
                    .semantics { contentDescription = description })
            Text(stringResource(if (config.timeSignature.pulseUnit == PulseUnit.EIGHTH) R.string.tempo_eighth else R.string.tempo_quarter),
                style = MaterialTheme.typography.labelMedium)
        }
        Column {
            TempoStep("+1", stringResource(R.string.increase_tempo), config.tempo.bpm < 300, expandedControls, onIncrease)
            TempoStep("+5", "Zwiększ tempo o 5 BPM", config.tempo.bpm < 300, expandedControls) { onAdjust(5) }
        }
    }
    }
    val sliderLabel = stringResource(R.string.tempo_slider)
    Slider(config.tempo.bpm.toFloat(), { onTempo(it.roundToInt().coerceIn(20, 300)) }, valueRange = 20f..300f,
        steps = 279, modifier = Modifier.fillMaxWidth().semantics { contentDescription = sliderLabel })
    if (!short) OutlinedButton(onTap, Modifier.fillMaxWidth().sizeIn(minHeight = if (large) 64.dp else 48.dp)) { Text(stringResource(R.string.tap_tempo)) }
    if (!short && !hideHint) Text(stringResource(R.string.tap_tempo_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Hoisted above layout branches so IME, rotation and resizing cannot dispose the editor. */
@Composable
private fun TempoEditorDialog(initialBpm: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
        var draft by rememberSaveable { mutableStateOf(initialBpm.toString()) }
        val bpm = draft.toIntOrNull()?.takeIf { it in 20..300 }
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Ustaw tempo") }, text = {
            OutlinedTextField(draft, { if (it.length <= 4 && it.all(Char::isDigit)) draft = it },
                label = { Text("BPM (20–300)") }, isError = bpm == null, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }, confirmButton = { TextButton({ bpm?.let(onConfirm) }, enabled = bpm != null) { Text("Zastosuj") } },
            dismissButton = { TextButton(onDismiss) { Text("Anuluj") } })
}

@Composable
private fun TempoStep(label: String, description: String, enabled: Boolean, large: Boolean, action: () -> Unit) {
    OutlinedButton(action, enabled = enabled, contentPadding = PaddingValues(8.dp),
        modifier = Modifier.sizeIn(minWidth = if (large) 80.dp else 52.dp, minHeight = if (large) 64.dp else 48.dp)
            .semantics { contentDescription = description }) { Text(label, style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge) }
}

@Composable
private fun InstrumentSurface(modifier: Modifier = Modifier, prominent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(if (prominent) 24.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Preview(name = "Telefon", widthDp = 360, heightDp = 800)
@Preview(name = "Tablet", widthDp = 800, heightDp = 600)
@Preview(name = "Duży tekst", widthDp = 360, heightDp = 800, fontScale = 1.6f)
@Composable
private fun InstrumentPreview() {
    org.openmetronome.ui.theme.OpenMetronomeTheme {
        MetronomeScreen(MetronomeState(), {}, {}, {}, {}, {}, {})
    }
}
