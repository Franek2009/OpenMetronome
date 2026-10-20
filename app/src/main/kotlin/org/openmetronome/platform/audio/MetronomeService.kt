package org.openmetronome.platform.audio

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import android.util.Log
import kotlinx.coroutines.*
import org.openmetronome.MainActivity
import org.openmetronome.OpenMetronomeApplication
import org.openmetronome.data.SessionRecorder
import kotlinx.coroutines.flow.*
import org.openmetronome.audio.*

class MetronomeService : Service() {
    inner class LocalBinder : Binder() {
        val controller get() = this@MetronomeService.controller
        fun stopPlayback() = this@MetronomeService.stopPlayback()
        fun startPlayback() {
            val token = requests.request()
            startForegroundService(Intent(this@MetronomeService, MetronomeService::class.java).setAction(START).putExtra("request", token))
        }
    }
    private lateinit var controller: MetronomeController
    private lateinit var media: MediaSession
    private lateinit var wake: PowerManager.WakeLock
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false
    private var waitingForStart = false
    private var lastPulseFrame = -1L
    private val settingsReady = CompletableDeferred<Unit>()
    private lateinit var recorder: SessionRecorder
    private var finishing: Job? = null
    private var playRequest: Job? = null
    private var notificationTempo: Int? = null
    private val requests = StartRequestGate()
    private var renewWakeAt = 0L

    override fun onCreate() {
        super.onCreate()
        controller = MetronomeController((application as OpenMetronomeApplication).createAudioEngine(this))
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenMetronome:Playback")
        wake.setReferenceCounted(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Odtwarzanie metronomu", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) },
        )
        media = MediaSession(this, "OpenMetronome").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onStop() = this@MetronomeService.stopPlayback()
                override fun onPause() = this@MetronomeService.stopPlayback()
            })
        }
        val data = (application as OpenMetronomeApplication).data
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { data.settings.load() }
                if (controller.configurationRevision == 0L) controller.editConfig { saved }
                else data.saveSettings(controller.state.value.config)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { data.error.value = "Nie udało się odczytać ustawień. Zachowano oryginalne dane." }
            settingsReady.complete(Unit)
            controller.state.map { it.config }.distinctUntilChanged().drop(1).collect(data::saveSettings)
        }
        // A separate finite load barrier, not the never-ending preferences collector.
        recorder = SessionRecorder(data)
        scope.launch(Dispatchers.IO) {
            controller.state.collect { state ->
                try { recorder.accept(state) }
                catch (_: Exception) { data.error.value = "Nie udało się zapisać sesji" }
            }
        }
        scope.launch {
            controller.state.collect { state ->
                if (foreground && state.keepsScreenOn && SystemClock.elapsedRealtime() >= renewWakeAt) holdCpu()
                val pulse = state.audio.pulse
                if (pulse != null && pulse.frame != lastPulseFrame) {
                    lastPulseFrame = pulse.frame
                    if (state.config.vibration && state.audio.status == PlaybackStatus.PLAYING) {
                        (application as OpenMetronomeApplication).vibratePulse()
                    }
                }
                if (state.audio.status == PlaybackStatus.STARTING || state.audio.status == PlaybackStatus.PLAYING || state.audio.status == PlaybackStatus.ERROR) waitingForStart = false
                if (foreground && !waitingForStart && (state.audio.status == PlaybackStatus.STOPPED || state.audio.status == PlaybackStatus.ERROR)) endForeground()
                if (foreground) {
                    media.isActive = true
                    media.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_STOP or PlaybackState.ACTION_PAUSE)
                        .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build())
                    val tempo = state.audio.activeConfig?.tempo?.bpm ?: state.config.tempo.bpm
                    if (notificationTempo != tempo) {
                        getSystemService(NotificationManager::class.java).notify(ID, notification(state))
                        notificationTempo = tempo
                    }
                }
            }
        }
    }
    override fun onBind(intent: Intent) = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopPlayback(); return START_NOT_STICKY }
        if (intent?.action != START) { stopSelf(); return START_NOT_STICKY }
        try {
            if (foreground && (waitingForStart || controller.state.value.keepsScreenOn)) return START_NOT_STICKY
            lastPulseFrame = -1L
            notificationTempo = null
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(controller.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID, notification(controller.state.value))
            foreground = true
            if (!requests.accepts(intent.getLongExtra("request", -1))) {
                endForeground()
                return START_NOT_STICKY
            }
            waitingForStart = true
            holdCpu()
            if (controller.state.value.keepsScreenOn) waitingForStart = false
            val token = intent.getLongExtra("request", -1)
            playRequest = scope.launch {
                settingsReady.await()
                finishing?.join()
                if (foreground && requests.accepts(token)) {
                    controller.start()
                    waitingForStart = false
                    if (controller.state.value.audio.status == PlaybackStatus.STOPPED || controller.state.value.audio.status == PlaybackStatus.ERROR) endForeground()
                }
            }
        } catch (error: RuntimeException) {
            Log.e("OpenMetronomeAudio", "foreground start failed", error)
            controller.stop()
            controller.reportFailure(AudioFailure.INITIALIZATION)
            endForeground()
        }
        return START_NOT_STICKY
    }
    private fun notification(state: MetronomeState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MetronomeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("OpenMetronome").setContentText("${state.audio.activeConfig?.tempo?.bpm ?: state.config.tempo.bpm} BPM · Stop, aby zakończyć")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(0)).build()
    }
    private fun endForeground() {
        if (!foreground) return
        foreground = false
        waitingForStart = false
        if (wake.isHeld) wake.release()
        val state = controller.state.value
        finishing = scope.launch {
            try { withContext(Dispatchers.IO) { recorder.accept(state) } }
            catch (_: Exception) { (application as OpenMetronomeApplication).data.error.value = "Nie udało się zapisać końca sesji" }
            if (!foreground) {
                if (wake.isHeld) wake.release()
                media.isActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
    private fun holdCpu() {
        // Renewed by playback state updates, never used to schedule beats.
        wake.acquire(120000L)
        renewWakeAt = SystemClock.elapsedRealtime() + 60000L
    }
    private fun stopPlayback() {
        Log.i("OpenMetronomeAudio", "explicit stop requested")
        requests.cancel()
        playRequest?.cancel()
        waitingForStart = false
        controller.stop()
        if (controller.state.value.audio.status == PlaybackStatus.STOPPED || controller.state.value.audio.status == PlaybackStatus.ERROR) endForeground()
    }
    override fun onDestroy() {
        if (controller.configurationRevision > 0) (application as OpenMetronomeApplication).data.saveSettings(controller.state.value.config)
        controller.close()
        scope.cancel()
        if (wake.isHeld) wake.release()
        media.release()
        super.onDestroy()
    }
    companion object {
        const val START = "org.openmetronome.START"
        const val STOP = "org.openmetronome.STOP"
        private const val CHANNEL = "metronome-playback"
        private const val ID = 1
    }
}
