package org.openmetronome

import android.os.Bundle
import android.media.AudioManager
import android.os.Process
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import org.openmetronome.ui.OpenMetronomeApp
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.openmetronome.audio.MetronomeController
import org.openmetronome.audio.PcmAudioEngine
import org.openmetronome.platform.audio.AndroidAudioFocusGate
import org.openmetronome.platform.audio.AndroidPcmSinkFactory
import org.openmetronome.platform.audio.PlaybackClient
import org.openmetronome.ui.MetronomeScreen
import org.openmetronome.ui.MetronomeViewModel
import org.openmetronome.ui.theme.OpenMetronomeTheme
import android.appwidget.AppWidgetManager
import org.openmetronome.widget.*

class MainActivity : ComponentActivity() {
    private data class WidgetRequest(val id: Int, val statistics: Boolean, val music: Boolean, val activityTemplate: String? = null)
    private var widgetRequest = 0
    private val pendingWidgetRequests = androidx.compose.runtime.mutableStateListOf<WidgetRequest>()
    private lateinit var model: MetronomeViewModel
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { recreate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetRequest = savedInstanceState?.getInt("widget_request") ?: 0
        val ids = savedInstanceState?.getIntArray("widget_pending_ids") ?: intArrayOf()
        val targets = savedInstanceState?.getBooleanArray("widget_pending_targets") ?: booleanArrayOf()
        val scopes = savedInstanceState?.getBooleanArray("widget_pending_scopes") ?: BooleanArray(ids.size)
        val templates = savedInstanceState?.getStringArray("widget_pending_templates") ?: Array(ids.size) { "" }
        ids.forEachIndexed { index, id -> pendingWidgetRequests.add(WidgetRequest(id, targets.getOrElse(index) { false }, scopes.getOrElse(index) { false }, templates.getOrElse(index) { "" }.ifEmpty { null })) }
        if (savedInstanceState == null && (intent.hasExtra("widget_statistics") || intent.hasExtra("widget_activity_id"))) enqueueWidgetRequest(intent)
        volumeControlStream = AudioManager.STREAM_MUSIC
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == MetronomeViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return MetronomeViewModel(PlaybackClient(applicationContext)) as T
            }
        })[MetronomeViewModel::class.java]
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            OpenMetronomeTheme {
                val request = pendingWidgetRequests.firstOrNull()
                OpenMetronomeApp(model, (application as OpenMetronomeApplication).data,
                    widgetStatistics = request?.statistics ?: false, widgetRequest = request?.id ?: 0,
                    widgetMusicScope = request?.music ?: false,
                    widgetActivityTemplate = request?.activityTemplate,
                    onWidgetRequestHandled = { id ->
                        if (pendingWidgetRequests.firstOrNull()?.id == id) pendingWidgetRequests.removeAt(0)
                    },
                    notificationPermission = if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        { { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) } } else null,
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("widget_statistics") || intent.hasExtra("widget_activity_id")) {
            enqueueWidgetRequest(intent)
        }
    }

    private fun enqueueWidgetRequest(intent: android.content.Intent) {
        val widgetId = intent.getIntExtra("widget_activity_id", AppWidgetManager.INVALID_APPWIDGET_ID)
        val activityTemplate = if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
            if (provider?.packageName != packageName || provider.className !in setOf(AddActivityWidget::class.java.name,
                    ActivityTemplateWidget::class.java.name, ActivityShortcutsWidget::class.java.name)) return
            val config = WidgetConfigurationStore(this).get(widgetId)
            val slot = intent.getIntExtra("widget_activity_slot", -1)
            val shortcut = when (provider.className) {
                AddActivityWidget::class.java.name -> WidgetShortcut()
                ActivityShortcutsWidget::class.java.name -> if (slot in 0..3) config.shortcuts.getOrNull(slot) ?: defaultWidgetShortcuts()[slot] else return
                else -> WidgetShortcut(config.category, config.instrument, config.durationMinutes)
            }
            WidgetConfiguration(category = shortcut.category, instrument = shortcut.instrument, durationMinutes = shortcut.durationMinutes).encode()
        } else null
        widgetRequest++
        pendingWidgetRequests.add(WidgetRequest(widgetRequest, intent.getBooleanExtra("widget_statistics", false), intent.getBooleanExtra("widget_music_scope", false), activityTemplate))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("widget_request", widgetRequest)
        outState.putIntArray("widget_pending_ids", pendingWidgetRequests.map { it.id }.toIntArray())
        outState.putBooleanArray("widget_pending_targets", pendingWidgetRequests.map { it.statistics }.toBooleanArray())
        outState.putBooleanArray("widget_pending_scopes", pendingWidgetRequests.map { it.music }.toBooleanArray())
        outState.putStringArray("widget_pending_templates", pendingWidgetRequests.map { it.activityTemplate.orEmpty() }.toTypedArray())
        super.onSaveInstanceState(outState)
    }

    override fun onStart() { super.onStart(); model.visibility(true) }

    override fun onStop() {
        model.visibility(false)
        model.resetTapTempo()
        Log.i("OpenMetronomeAudio", "Activity.onStop changingConfigurations=$isChangingConfigurations")
        super.onStop()
    }
}
