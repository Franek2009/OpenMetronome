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

class MainActivity : ComponentActivity() {
    private var widgetRequest by androidx.compose.runtime.mutableIntStateOf(0)
    private var widgetStatistics by androidx.compose.runtime.mutableStateOf(false)
    private lateinit var model: MetronomeViewModel
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { recreate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetStatistics = intent.getBooleanExtra("widget_statistics", false)
        widgetRequest = if (savedInstanceState == null && intent.hasExtra("widget_statistics")) 1 else 0
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
                OpenMetronomeApp(model, (application as OpenMetronomeApplication).data,
                    widgetStatistics = widgetStatistics, widgetRequest = widgetRequest,
                    notificationPermission = if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        { { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) } } else null,
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("widget_statistics")) {
            widgetStatistics = intent.getBooleanExtra("widget_statistics", false)
            widgetRequest++
        }
    }

    override fun onStart() { super.onStart(); model.visibility(true) }

    override fun onStop() {
        model.visibility(false)
        model.resetTapTempo()
        Log.i("OpenMetronomeAudio", "Activity.onStop changingConfigurations=$isChangingConfigurations")
        super.onStop()
    }
}
