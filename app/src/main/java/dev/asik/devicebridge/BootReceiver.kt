package dev.asik.devicebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.asik.devicebridge.service.BridgeForegroundService
import dev.asik.devicebridge.service.ServiceKeepAlive
import dev.asik.devicebridge.util.BridgePrefs
import dev.asik.devicebridge.util.ErrorLog

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val isBootish =
            action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (!isBootish) return

        // Start when the user opted into boot, or when they left the service "on"
        // before reboot / update (serviceDesired survives process death).
        val shouldStart =
            BridgePrefs.startOnBoot(context) || BridgePrefs.serviceDesired(context)
        if (!shouldStart) return

        ErrorLog.info("boot_start", "Starting bridge after $action")
        BridgeRuntime.init(context)
        // Ensure desired is set so swipe/kill recovery stays armed after boot.
        ServiceKeepAlive.markDesired(context, true)
        BridgeForegroundService.start(context)
    }
}
