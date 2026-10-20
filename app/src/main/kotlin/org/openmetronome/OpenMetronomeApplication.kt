package org.openmetronome

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import org.openmetronome.audio.AudioEngine
import org.openmetronome.audio.PcmAudioEngine
import org.openmetronome.platform.audio.AndroidAudioFocusGate
import org.openmetronome.platform.audio.AndroidPcmSinkFactory
import org.openmetronome.data.AppData

open class OpenMetronomeApplication : Application() {
    open val data: AppData by lazy { AppData(this) }

    /** Platform composition root; instrumentation can guard or replace hardware output. */
    open fun createAudioEngine(context: Context): AudioEngine {
        val manager = context.getSystemService(AudioManager::class.java)
        return PcmAudioEngine(
            AndroidPcmSinkFactory(manager), AndroidAudioFocusGate(context, manager),
            configureThread = { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) },
            log = { message, error ->
                if (error == null) Log.i("OpenMetronomeAudio", message)
                else Log.e("OpenMetronomeAudio", message, error)
            },
        )
    }

    open fun vibratePulse() {
        getSystemService(Vibrator::class.java)?.vibrate(
            VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE),
        )
    }
}
