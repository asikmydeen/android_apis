package dev.asik.devicebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.asik.devicebridge.service.BridgeForegroundService
import dev.asik.devicebridge.util.BridgePrefs
import dev.asik.devicebridge.util.ErrorLog

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        if (!BridgePrefs.startOnBoot(context)) return
        ErrorLog.info("boot_start", "Starting bridge after $action")
        BridgeRuntime.init(context)
        BridgeForegroundService.start(context)
    }
}
