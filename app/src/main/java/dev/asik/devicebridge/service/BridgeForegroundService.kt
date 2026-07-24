package dev.asik.devicebridge.service

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
                // Explicit user stop — do not auto-restart.
                ServiceKeepAlive.markDesired(this, false)
                scope.launch {
                    releaseLocks()
                    BridgeRuntime.stopServer()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            // Re-apply startForeground with the current type mask (e.g. after the
            // user enables the microphone collector). Without this, Android 14+
            // keeps the old type set and AudioRecord only works while the app UI
            // is in the foreground — remote PCM clients get digital silence.
            ACTION_REFRESH_TYPES -> {
                promoteToForeground()
                updateNotification()
                return START_STICKY
            }
            else -> {
                // User (or keepalive) wants the service up — persist across kills.
                ServiceKeepAlive.markDesired(this, true)
                promoteToForeground()
                acquireLocks()
                scope.launch {
                    runCatching {
                        BridgeRuntime.startServer()
                        // Re-assert types after collectors start so a late-granted
                        // RECORD_AUDIO (or streamAudio pref) is reflected immediately.
                        promoteToForeground()
                        updateNotification()
                        ServiceKeepAlive.scheduleWatchdog(this@BridgeForegroundService)
                    }.onFailure {
                        releaseLocks()
                        // Keep desired=true so the watchdog / alarm can retry.
                        ServiceKeepAlive.scheduleRestart(this@BridgeForegroundService, "start_failed")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    /** startForeground with the type mask that matches current perms + prefs. */
    private fun promoteToForeground() {
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
        // Swiping the task away must NOT end the bridge — re-assert FGS + schedule
        // a restart if the process is still about to be reaped.
        if (ServiceKeepAlive.isDesired(this)) {
            promoteToForeground()
            ServiceKeepAlive.scheduleRestart(this, "task_removed")
            ServiceKeepAlive.scheduleWatchdog(this)
        }
    }

    /**
     * Build the FGS type bitmask for the capabilities we actually use right now.
     *
     * Android 14+ (UPSIDE_DOWN_CAKE) requires [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE]
     * on the active foreground service or background [android.media.AudioRecord] is
     * denied / returns silence — which is exactly what remote PCM clients saw when
     * the app left the foreground.
     *
     * Microphone is only claimed when the user has both RECORD_AUDIO and the
     * Microphone collector enabled, so we don't hold a mic FGS type idle.
     */
    private fun foregroundServiceType(): Int {
        val hasLocation = PermissionHelper.hasLocation(this)
        val hasCamera = PermissionHelper.hasCamera(this)
        // Claim MIC whenever the collector is on (levels and/or raw PCM both need it).
        // Without this bit, Android 14+ feeds silence to AudioRecord in the background.
        val hasMic =
            PermissionHelper.hasMicrophone(this) &&
                (BridgePrefs.streamAudio(this) || BridgePrefs.streamRawAudio(this))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (hasLocation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (hasCamera) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            type
        } else {
            // Pre-34 typed FGS constants: LOCATION is the portable one. Camera/mic
            // type bits only exist on API 34+.
            if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        }
    }

    override fun onDestroy() {
        val wantRestart = ServiceKeepAlive.isDesired(this)
        releaseLocks()
        // Never block the main thread here — onDestroy runs on the main thread and
        // engine.stop() can take up to ~2s, an ANR during system-initiated teardown.
        // The clean-stop path (ACTION_STOP) already stops the server asynchronously;
        // this is best-effort cleanup on an independent scope for the killed case.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { BridgeRuntime.stopServer() }
        }
        if (wantRestart) {
            // System is tearing us down (LMK / OEM killer). Schedule a bounce.
            ServiceKeepAlive.scheduleRestart(this, "service_destroyed")
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
        const val ACTION_REFRESH_TYPES = "dev.asik.devicebridge.REFRESH_TYPES"

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

        /**
         * Re-apply the FGS type mask while the service is already running.
         * Call after toggling Microphone / granting RECORD_AUDIO so background
         * capture picks up FOREGROUND_SERVICE_TYPE_MICROPHONE without a full stop.
         */
        fun refreshTypes(context: Context) {
            if (!BridgeRuntime.running.value) return
            context.startService(
                Intent(context, BridgeForegroundService::class.java)
                    .setAction(ACTION_REFRESH_TYPES),
            )
        }
    }
}
