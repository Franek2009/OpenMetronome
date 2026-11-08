package org.openmetronome.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.os.IBinder
import android.widget.Toast
import kotlinx.coroutines.*
import org.openmetronome.platform.audio.MetronomeService
import kotlin.coroutines.resume

open class MetronomeWidget : AppWidgetProvider() {
    override fun onDeleted(context: Context, appWidgetIds: IntArray) { WidgetHost.widgetsChanged(context) }
    override fun onDisabled(context: Context) { WidgetHost.widgetsChanged(context) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(AppWidgetManager.ACTION_APPWIDGET_UPDATE,
                AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED, Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.Main.immediate).launch {
                try { withTimeout(8500) { WidgetHost.refresh(context.applicationContext) } }
                finally { pending.finish() }
            }
        }
    }
}
class MiniWidget : MetronomeWidget()
class ControlWidget : MetronomeWidget()
class StatisticsWidget : MetronomeWidget()

/** Only immutable, explicit PendingIntents can reach this non-exported receiver. */
class WidgetCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.action?.takeIf { it in WidgetModel.commands } ?: return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            var bound = false
            var connection: ServiceConnection? = null
            try {
                withTimeout(8000) {
                    val binder = suspendCancellableCoroutine<MetronomeService.LocalBinder> { continuation ->
                        connection = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                                if (continuation.isActive) continuation.resume(service as MetronomeService.LocalBinder)
                            }
                            override fun onServiceDisconnected(name: ComponentName) {
                                if (continuation.isActive) continuation.cancel()
                            }
                            override fun onNullBinding(name: ComponentName) { if (continuation.isActive) continuation.cancel() }
                        }
                        bound = app.bindService(Intent(app, MetronomeService::class.java), connection, Context.BIND_AUTO_CREATE)
                        if (!bound) continuation.cancel()
                    }
                    binder.widgetCommand(command)
                }
            } catch (_: Exception) {
                Toast.makeText(app, "Nie wykonano polecenia. Otwórz OpenMetronome.", Toast.LENGTH_SHORT).show()
            } finally {
                if (bound) connection?.let { app.unbindService(it) }
                try { withTimeout(1000) { WidgetHost.refresh(app) } } finally { pending.finish() }
            }
        }
    }
}
