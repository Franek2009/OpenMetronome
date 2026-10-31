package org.openmetronome

import android.app.Service
import android.content.Intent
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.openmetronome.data.AppData
import org.openmetronome.data.RestoreCheckpoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Debug-only, separate process. All storage is a UUID fixture under cache, never application data. */
class RestoreCrashProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Message>(Channel.UNLIMITED)
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { message ->
        requests.trySend(Message.obtain().also { it.copyFrom(message) }); true
    })
    override fun onCreate() {
        super.onCreate()
        val ownProcess = getSystemService(android.app.ActivityManager::class.java).runningAppProcesses
            ?.singleOrNull { it.pid == Process.myPid() }
        check(ownProcess?.processName == packageName + ":restore_probe") { "Crash probe must run in its isolated process" }
        scope.launch {
            for (request in requests) {
                val result = Bundle()
                var data: AppData? = null
                try {
                    check(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    val id = requireNotNull(request.data.getString("fixture"))
                    require(UUID.fromString(id).toString() == id)
                    val directory = File(cacheDir, "restore-probes/$id").also { check(it.isDirectory || it.mkdirs()) }
                    val cut = request.data.getString("cut")?.let(RestoreCheckpoint::valueOf)
                    data = AppData(this@RestoreCrashProbeService, directory) { checkpoint ->
                        if (checkpoint == cut) {
                            FileOutputStream(File(directory, "process-cut")).use {
                                it.write(checkpoint.name.toByteArray()); it.fd.sync()
                            }
                            // Kill only :restore_probe, whose AppData is strictly the fixture above.
                            Process.killProcess(Process.myPid())
                            kotlin.system.exitProcess(99)
                        }
                    }
                    data.ready.await()
                    val archive = request.data.getByteArray("archive")
                    if (archive != null) {
                        data.importBackup({ ByteArrayInputStream(archive) }, {})
                        data.awaitWrites()
                        check(data.backupNotice.value?.startsWith("Kopia została przywrócona") == true) { data.backupNotice.value.orEmpty() }
                    }
                    val output = ByteArrayOutputStream()
                    data.exportBackup { output }; data.awaitWrites()
                    check(data.backupNotice.value == "Kopia zapasowa została zapisana.") { data.backupNotice.value.orEmpty() }
                    result.putByteArray("archive", output.toByteArray())
                    result.putInt("pid", Process.myPid())
                } catch (error: Exception) {
                    result.putString("error", error.javaClass.simpleName + ": " + error.message.orEmpty())
                } finally { withContext(NonCancellable) { data?.closeTestStorage() } }
                runCatching { request.replyTo?.send(Message.obtain().apply { this.data = result }) }
                request.recycle()
            }
        }
    }
    override fun onBind(intent: Intent): IBinder = endpoint.binder
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
