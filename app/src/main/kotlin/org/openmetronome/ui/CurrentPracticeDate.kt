package org.openmetronome.ui

import android.content.*
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.openmetronome.data.millisUntilNextPracticeDate

/** Collected only while statistics are visible/STARTED; no service or audio-thread clock. */
internal fun observePracticeDate(context: Context, now: () -> ZonedDateTime = { ZonedDateTime.now() }): Flow<LocalDate> = callbackFlow {
    val changes = Channel<Unit>(Channel.CONFLATED)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { changes.trySend(Unit) }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }
    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else { @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter) }
    val ticker = launch {
        while (isActive) {
            val current = now()
            trySend(current.toLocalDate())
            withTimeoutOrNull(millisUntilNextPracticeDate(current)) { changes.receive() }
        }
    }
    // The local wakeup channel owns no resource. Cancelling its receiver is enough;
    // closing it concurrently can race receive() and escape as ClosedReceiveChannelException.
    awaitClose { ticker.cancel(); context.unregisterReceiver(receiver) }
}.distinctUntilChanged()

@Composable
internal fun rememberPracticeDate(): State<LocalDate> {
    val context = LocalContext.current.applicationContext
    val source = remember(context) { observePracticeDate(context) }
    return source.collectAsStateWithLifecycle(initialValue = LocalDate.now())
}
