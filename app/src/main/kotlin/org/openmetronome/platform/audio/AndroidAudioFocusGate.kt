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

class AndroidAudioFocusGate(
    private val context: Context,
    private val manager: AudioManager,
) : AudioFocusGate {
    override fun acquire(onLoss: () -> Unit): AutoCloseable? {
        val active = AtomicBoolean(true)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(metronomeAudioAttributes())
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN && active.get()) {
                    Log.i("OpenMetronomeAudio", "stop requested by audio focus change=$change")
                    onLoss()
                }
            }, Handler(Looper.getMainLooper()))
            .build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            active.set(false)
            return null
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && active.get()) {
                    Log.i("OpenMetronomeAudio", "stop requested by AUDIO_BECOMING_NOISY")
                    onLoss()
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
            manager.abandonAudioFocusRequest(request)
            throw error
        }
        return AutoCloseable {
            if (active.getAndSet(false)) {
                try { context.unregisterReceiver(receiver) }
                finally { manager.abandonAudioFocusRequest(request) }
            }
        }
    }
}
