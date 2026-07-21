package dev.asik.devicebridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.asik.devicebridge.ui.DeviceBridgeAppUi
import dev.asik.devicebridge.ui.theme.DeviceBridgeTheme
import dev.asik.devicebridge.util.BridgePrefs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeRuntime.init(this)
        enableEdgeToEdge()
        setContent {
            val themeMode by BridgePrefs.themeModeFlow(this).collectAsState()
            DeviceBridgeTheme(themeMode = themeMode) {
                DeviceBridgeAppUi()
            }
        }
    }
}
