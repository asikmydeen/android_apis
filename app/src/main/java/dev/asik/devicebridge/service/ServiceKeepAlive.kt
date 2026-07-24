package dev.asik.devicebridge.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import dev.asik.devicebridge.BridgeRuntime
import dev.asik.devicebridge.util.BridgePrefs
import dev.asik.devicebridge.util.ErrorLog

/**
 * Keeps [BridgeForegroundService] alive across swipe-away, process death, and Doze.
 *
 * Strategy:
 * - Persist "user wants the bridge running" in [BridgePrefs.serviceDesired]
 * - On unexpected stop, schedule an exact-while-idle restart alarm (Samsung-friendly)
 * - Periodic watchdog alarm restarts the service if it died quietly
 * - Boot / package-replaced handlers honor start-on-boot + desired flags
 */
object ServiceKeepAlive {
    private const val REQ_RESTART = 4101
    private const val REQ_WATCHDOG = 4102
    private const val RESTART_DELAY_MS = 1_500L
    /** How often to verify the FGS is still up while the user wants it running. */
    private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

    const val ACTION_RESTART = "dev.asik.devicebridge.action.RESTART_SERVICE"
    const val ACTION_WATCHDOG = "dev.asik.devicebridge.action.WATCHDOG"

    fun markDesired(context: Context, desired: Boolean) {
        BridgePrefs.setServiceDesired(context, desired)
        if (desired) {
            scheduleWatchdog(context)
        } else {
            cancelAll(context)
        }
    }

    fun isDesired(context: Context): Boolean = BridgePrefs.serviceDesired(context)

    /** True when the bridge should be (re)started automatically. */
    fun shouldAutoStart(context: Context): Boolean {
        return BridgePrefs.serviceDesired(context) || BridgePrefs.startOnBoot(context)
    }

    fun ensureRunning(context: Context, reason: String) {
        if (!isDesired(context) && !BridgePrefs.startOnBoot(context)) return
        // startOnBoot alone only applies at boot; for runtime ensure use serviceDesired
        if (!isDesired(context)) return
        if (BridgeRuntime.running.value) return
        ErrorLog.info("keepalive_start", "Starting bridge ($reason)")
        BridgeRuntime.init(context.applicationContext)
        BridgeForegroundService.start(context.applicationContext)
    }

    fun scheduleRestart(context: Context, reason: String) {
        if (!isDesired(context)) return
        ErrorLog.info("keepalive_schedule", "Restart in ${RESTART_DELAY_MS}ms ($reason)")
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pending(context, REQ_RESTART, ACTION_RESTART)
        val triggerAt = SystemClock.elapsedRealtime() + RESTART_DELAY_MS
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                @Suppress("DEPRECATION")
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }.onFailure {
            // Fallback: try inexact
            runCatching {
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pi,
                )
            }
        }
    }

    fun scheduleWatchdog(context: Context) {
        if (!isDesired(context)) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pending(context, REQ_WATCHDOG, ACTION_WATCHDOG)
        val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
        runCatching {
            // Inexact is fine for a 15‑min health check and avoids exact-alarm policy pain.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pi,
                )
            } else {
                @Suppress("DEPRECATION")
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pending(context, REQ_RESTART, ACTION_RESTART)) }
        runCatching { am.cancel(pending(context, REQ_WATCHDOG, ACTION_WATCHDOG)) }
    }

    private fun pending(context: Context, req: Int, action: String): PendingIntent {
        val intent = Intent(context, KeepAliveReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            req,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Receives restart / watchdog alarms and bootstraps the foreground service.
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ServiceKeepAlive.ACTION_RESTART,
            ServiceKeepAlive.ACTION_WATCHDOG,
            -> {
                ServiceKeepAlive.ensureRunning(context, action.substringAfterLast('.').lowercase())
                // Always re-arm the watchdog while still desired.
                if (ServiceKeepAlive.isDesired(context)) {
                    ServiceKeepAlive.scheduleWatchdog(context)
                }
            }
        }
    }
}
