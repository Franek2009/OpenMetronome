package org.openmetronome.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openmetronome.R
import org.openmetronome.audio.*

/** Outside scrolling settings; the same actions remain available on every tab. */
@Composable
fun PlaybackControls(state: MetronomeState, onStart: () -> Unit, onStop: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        BoxWithConstraints {
        val expanded = maxWidth >= 600.dp && maxHeight >= 600.dp
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = if (state.keepsScreenOn) onStop else onStart,
                enabled = state.audio.status != PlaybackStatus.STOPPING,
                modifier = Modifier.widthIn(max = if (expanded) 900.dp else 560.dp).fillMaxWidth()
                    .sizeIn(minHeight = if (expanded) 64.dp else 56.dp)) {
                Text(stringResource(when (state.audio.status) {
                    PlaybackStatus.STARTING, PlaybackStatus.PLAYING -> R.string.stop
                    PlaybackStatus.STOPPING -> R.string.stopping
                    PlaybackStatus.ERROR -> R.string.retry
                    PlaybackStatus.STOPPED -> R.string.start
                }))
            }
            Text(stringResource(when {
                state.audio.status == PlaybackStatus.ERROR -> when (state.audio.failure) {
                    AudioFailure.FOCUS_DENIED -> R.string.audio_focus_denied
                    AudioFailure.INITIALIZATION -> R.string.audio_initialization_failed
                    AudioFailure.DEVICE_LOST -> R.string.audio_device_lost
                    else -> R.string.audio_playback_failed
                }
                state.hasPendingChanges -> R.string.pending_change
                state.audio.status == PlaybackStatus.STARTING -> R.string.starting
                state.audio.status == PlaybackStatus.PLAYING -> R.string.playing
                else -> R.string.foreground_notice
            }), modifier = Modifier.widthIn(max = 560.dp), maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall)
        }
        }
    }
}
