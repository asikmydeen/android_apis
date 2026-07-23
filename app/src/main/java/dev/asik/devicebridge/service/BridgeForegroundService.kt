package dev.asik.devicebridge.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.asik.devicebridge.BridgeRuntime
import dev.asik.devicebridge.MainActivity
import dev.asik.devicebridge.R
import dev.asik.devicebridge.util.BridgePrefs
import dev.asik.devicebridge.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BridgeForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLockRenewJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BridgeRuntime.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    releaseLocks()
                    BridgeRuntime.stopServer()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        foregroundServiceType(),
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                acquireLocks()
                scope.launch {
                    runCatching {
                        BridgeRuntime.startServer()
                        updateNotification()
                    }.onFailure {
                        releaseLocks()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun acquireLocks() {
        if (!BridgePrefs.keepAwake(this)) return
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "DeviceBridge::ServiceWakeLock",
                )?.apply {
                    setReferenceCounted(false)
                    acquire(WAKELOCK_GRANT_MS)
                }
                // A single acquire() expires at its timeout and is never renewed,
                // so an always-on bridge silently loses the lock. Re-acquire on a
                // timer safely under the grant window to keep it held indefinitely.
                wakeLockRenewJob?.cancel()
                wakeLockRenewJob = scope.launch {
                    while (isActive) {
                        delay(WAKELOCK_RENEW_MS)
                        runCatching { wakeLock?.acquire(WAKELOCK_GRANT_MS) }
                    }
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "DeviceBridge::ServiceWifiLock",
                )?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun releaseLocks() {
        wakeLockRenewJob?.cancel()
        wakeLockRenewJob = null
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (BridgeRuntime.running.value) {
            val restartServiceIntent = Intent(applicationContext, BridgeForegroundService::class.java)
            val restartPendingIntent = PendingIntent.getService(
                applicationContext,
                1,
                restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
            val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            // A plain RTC set() is heavily deferred (or dropped) by Doze / App-Standby
            // on Samsung — the stated target device — so the bridge often never came
            // back after swipe-away. setExactAndAllowWhileIdle + RTC_WAKEUP fires even
            // in idle, restoring the always-reachable guarantee.
            runCatching {
                alarmService?.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 1000,
                    restartPendingIntent,
                )
            }
        }
    }

    private fun foregroundServiceType(): Int {
        val hasLocation = PermissionHelper.hasLocation(this)
        val hasCamera = PermissionHelper.hasCamera(this)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (hasLocation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (hasCamera) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            type
        } else {
            if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        }
    }

    override fun onDestroy() {
        releaseLocks()
        // Never block the main thread here — onDestroy runs on the main thread and
        // engine.stop() can take up to ~2s, an ANR during system-initiated teardown.
        // The clean-stop path (ACTION_STOP) already stops the server asynchronously;
        // this is best-effort cleanup on an independent scope for the killed case.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { BridgeRuntime.stopServer() }
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BridgeForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val port = BridgePrefs.port(this)
        val mode = BridgePrefs.networkMode(this).name
        val statusText = "Listening on port $port ($mode)"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val CHANNEL_ID = "bridge_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "dev.asik.devicebridge.STOP"

        // Wake lock: request a bounded grant and renew comfortably before it lapses,
        // so the lock is held for the whole session without an unbounded acquire.
        private const val WAKELOCK_GRANT_MS = 60 * 60 * 1000L      // 1 hour
        private const val WAKELOCK_RENEW_MS = 50 * 60 * 1000L      // renew every 50 min

        fun start(context: Context) {
            val intent = Intent(context, BridgeForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BridgeForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
