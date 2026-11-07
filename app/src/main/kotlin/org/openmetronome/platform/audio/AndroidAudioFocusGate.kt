package org.openmetronome.platform.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import org.openmetronome.audio.AudioFocusGate
import org.openmetronome.domain.audio.PcmTrace
import org.openmetronome.domain.audio.PcmTraceKind
import org.openmetronome.diagnostics.TraceControlKind

class AndroidAudioFocusGate(
    private val context: Context,
    private val manager: AudioManager,
) : AudioFocusGate {
    override fun acquire(onLoss: () -> Unit): AutoCloseable? = acquire(onLoss, null, null)

    override fun acquire(onLoss: () -> Unit, trace: PcmTrace?,
                         control: ((TraceControlKind, Long) -> Unit)?): AutoCloseable? {
        val active = AtomicBoolean(true)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(metronomeAudioAttributes())
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                val observed = active.get()
                if (change != AudioManager.AUDIOFOCUS_GAIN && observed) {
                    Log.i("OpenMetronomeAudio", "stop requested by audio focus change=$change")
                    onLoss()
                }
                if (observed) {
                    try { control?.invoke(TraceControlKind.FOCUS_CHANGE, change.toLong()) } catch (_: Exception) { }
                }
            }, Handler(Looper.getMainLooper()))
            .build()
        trace?.record(PcmTraceKind.FOCUS_REQUESTED)
        val result = manager.requestAudioFocus(request)
        trace?.record(PcmTraceKind.FOCUS_RESULT, a = result.toLong())
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            active.set(false)
            return null
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && active.get()) {
                    Log.i("OpenMetronomeAudio", "stop requested by AUDIO_BECOMING_NOISY")
                    onLoss()
                    try { control?.invoke(TraceControlKind.OUTPUT_NOISY, 0) } catch (_: Exception) { }
                }
            }
        }
        try {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        } catch (error: Exception) {
            active.set(false)
            val released = manager.abandonAudioFocusRequest(request)
            trace?.record(PcmTraceKind.FOCUS_RELEASE, a = released.toLong())
            throw error
        }
        return AutoCloseable {
            if (active.getAndSet(false)) {
                try { context.unregisterReceiver(receiver) }
                finally {
                    val released = manager.abandonAudioFocusRequest(request)
                    trace?.record(PcmTraceKind.FOCUS_RELEASE, a = released.toLong())
                }
            }
        }
    }
}
