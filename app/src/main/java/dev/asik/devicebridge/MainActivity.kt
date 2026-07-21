package dev.asik.devicebridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.asik.devicebridge.ui.DeviceBridgeAppUi
import dev.asik.devicebridge.ui.theme.DeviceBridgeTheme
// DeviceBridgeAppUi: bottom nav Home / Remote / Devices / Settings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeRuntime.init(this)
        enableEdgeToEdge()
        setContent {
            DeviceBridgeTheme {
                DeviceBridgeAppUi()
            }
        }
    }
}
