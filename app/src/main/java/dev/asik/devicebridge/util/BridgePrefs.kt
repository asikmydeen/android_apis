package dev.asik.devicebridge.util

import android.content.Context

object BridgePrefs {
    private const val NAME = "device_bridge_prefs"
    private const val KEY_START_ON_BOOT = "start_on_boot"

    fun startOnBoot(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_START_ON_BOOT, false)

    fun setStartOnBoot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_START_ON_BOOT, enabled)
            .apply()
    }
}
