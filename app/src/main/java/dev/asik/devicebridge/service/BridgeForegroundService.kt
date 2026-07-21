package dev.asik.devicebridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.asik.devicebridge.BridgeRuntime
import dev.asik.devicebridge.MainActivity
import dev.asik.devicebridge.R
import dev.asik.devicebridge.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class BridgeForegroundService : Service() {

    // Off the main thread: stopServer() calls Ktor engine.stop(), which blocks up to ~3s.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
                scope.launch {
                    runCatching { BridgeRuntime.startServer() }
                        .onFailure {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                }
            }
        }
        return START_STICKY
    }

    /**
     * Build the FGS type mask from permissions actually held right now.
     *
     * Android 14+ (and 10+ for the location type) throws SecurityException if a service
     * foregrounds with a `location`/`camera` type whose runtime permission is not granted,
     * so the type must never claim more than we hold. SPECIAL_USE (API 34+) is always safe
     * here because it is backed by the manifest PROPERTY_SPECIAL_USE_FGS_SUBTYPE.
     */
    private fun foregroundServiceType(): Int {
        val hasLocation = PermissionHelper.hasLocation(this)
        val hasCamera = PermissionHelper.hasCamera(this)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (hasLocation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (hasCamera) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            type
        } else {
            // API 29-33: no SPECIAL_USE type. Only declare LOCATION when granted;
            // otherwise start typeless (0) to avoid a permission-mismatch crash.
            if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        }
    }

    override fun onDestroy() {
        // Run teardown to completion, THEN cancel the scope. Cancelling first (the old bug)
        // killed stopServer() mid-flight, leaking the Ktor engine + all collector listeners.
        // On the normal ACTION_STOP path the server is already stopped, so this is a fast
        // no-op; the bounded runBlocking only does work on a direct system kill, where a
        // brief block to release the engine beats leaking it for the process lifetime.
        runBlocking {
            withTimeoutOrNull(STOP_TIMEOUT_MS) {
                runCatching { BridgeRuntime.stopServer() }
            }
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "bridge_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "dev.asik.devicebridge.STOP"

        // Upper bound for onDestroy teardown; Ktor engine.stop() uses ~2s (1s grace + 1s).
        private const val STOP_TIMEOUT_MS = 3000L

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
