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
    private lateinit var model: MetronomeViewModel
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { recreate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                val state by model.state.collectAsStateWithLifecycle()
                DisposableEffect(state.keepsScreenOn) {
                    if (state.keepsScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                OpenMetronomeApp(model, (application as OpenMetronomeApplication).data,
                    notificationPermission = if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        { { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) } } else null,
                )
            }
        }
    }

    override fun onStop() {
        model.resetTapTempo()
        Log.i("OpenMetronomeAudio", "Activity.onStop changingConfigurations=$isChangingConfigurations")
        super.onStop()
    }
}
