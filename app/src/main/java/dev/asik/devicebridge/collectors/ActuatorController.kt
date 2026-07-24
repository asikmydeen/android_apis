package dev.asik.devicebridge.collectors

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.VibratorManager
import android.os.Vibrator
import android.os.VibrationEffect
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import dev.asik.devicebridge.R

/**
 * Output-side device actions an agent can drive: launch apps, fire intents, speak,
 * flash the torch, vibrate, and post local notifications. All are "safe" actuation —
 * no Play-restricted permissions, no accessibility. Reads run through the collectors;
 * this is the write side.
 *
 * Every method returns a Result-style Pair(ok, message) so routes stay thin.
 */
class ActuatorController(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var notifyCounter = 0

    // ---- App / intent -------------------------------------------------

    fun launchApp(pkg: String): Pair<Boolean, String> {
        // Launching from a non-Activity context (the Ktor server) REQUIRES NEW_TASK
        // or Android throws. getLaunchIntentForPackage returns null if not installed.
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return false to "not installed: $pkg"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val direct = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        // Android 10+ Background Activity Launch often blocks service→activity starts
        // even when startActivity doesn't throw. Always offer a tappable notification.
        postDeepLinkNotification("Open app", pkg, intent)
        return if (direct) {
            true to "launched $pkg (also posted notification)"
        } else {
            true to "background launch blocked; tap notification to open $pkg"
        }
    }

    fun fireIntent(action: String, uri: String?, pkg: String?): Pair<Boolean, String> {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!uri.isNullOrBlank()) intent.data = Uri.parse(uri)
        if (!pkg.isNullOrBlank()) intent.setPackage(pkg)

        // Verify something can handle it (unless package forces a specific app).
        if (pkg.isNullOrBlank()) {
            val resolved = intent.resolveActivity(context.packageManager)
            if (resolved == null) {
                return false to "no app can handle $action ${uri ?: ""}".trim()
            }
        }

        val direct = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        val label = when {
            !uri.isNullOrBlank() && uri.contains("maps", ignoreCase = true) -> "Open directions"
            !uri.isNullOrBlank() -> "Open link"
            else -> "Open"
        }
        val body = (uri ?: action).take(120)
        postDeepLinkNotification(label, body, intent)

        return if (direct) {
            true to "fired $action (also posted notification — tap if nothing appeared)"
        } else {
            true to "background launch blocked; tap the notification to open"
        }
    }

    /**
     * High-importance notification whose content intent opens [intent].
     * This is the reliable path when the bridge runs as a FGS without a visible Activity
     * (Android Background Activity Launch restrictions).
     */
    private fun postDeepLinkNotification(title: String, body: String, intent: Intent): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                DEEP_LINK_CHANNEL_ID,
                "Bridge open links",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Tappable prompts when SensIO opens maps, URLs, or apps"
                enableVibration(true)
            }
            nm.createNotificationChannel(ch)
        }
        val req = ACTION_NOTIFICATION_ID_BASE + (notifyCounter++ and 0xFFFF)
        val pi = PendingIntent.getActivity(
            context,
            req,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return runCatching {
            val n = NotificationCompat.Builder(context, DEEP_LINK_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            nm.notify(req, n)
            true
        }.getOrDefault(false)
    }

    // ---- Text-to-speech ----------------------------------------------

    fun speak(text: String): Pair<Boolean, String> {
        if (text.isBlank()) return false to "empty text"
        // Lazily init the engine; first call may drop the utterance if not yet ready.
        val engine = tts ?: TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }.also { tts = it }
        if (!ttsReady) {
            // Give the engine a beat; TTS init is async. Report queued either way.
            return runCatching {
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bridge-tts")
                true to "queued (engine warming up)"
            }.getOrElse { false to (it.message ?: "tts failed") }
        }
        return runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bridge-tts")
            true to "speaking"
        }.getOrElse { false to (it.message ?: "tts failed") }
    }

    // ---- Torch --------------------------------------------------------

    fun torch(on: Boolean): Pair<Boolean, String> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return false to "no camera service"
        return runCatching {
            // Use the first camera that has a flash unit.
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false to "no flash unit"
            cm.setTorchMode(id, on)
            true to "torch ${if (on) "on" else "off"}"
        }.getOrElse { false to (it.message ?: "torch failed") }
    }

    // ---- Vibrate ------------------------------------------------------

    fun vibrate(ms: Long): Pair<Boolean, String> {
        val duration = ms.coerceIn(1, 10_000)
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator ?: return false to "no vibrator"
        if (!vibrator.hasVibrator()) return false to "device has no vibrator"
        return runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            true to "vibrated ${duration}ms"
        }.getOrElse { false to (it.message ?: "vibrate failed") }
    }

    // ---- Local notification ------------------------------------------

    fun notify(title: String, body: String): Pair<Boolean, String> {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false to "no notification service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                ACTION_CHANNEL_ID,
                "Bridge actions",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            nm.createNotificationChannel(ch)
        }
        return runCatching {
            val n = NotificationCompat.Builder(context, ACTION_CHANNEL_ID)
                .setContentTitle(title.ifBlank { "Device Bridge" })
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setAutoCancel(true)
                .build()
            nm.notify(ACTION_NOTIFICATION_ID_BASE + (notifyCounter++ and 0xFFFF), n)
            true to "notified"
        }.getOrElse { false to (it.message ?: "notify failed") }
    }

    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    companion object {
        private const val ACTION_CHANNEL_ID = "bridge_actions"
        private const val DEEP_LINK_CHANNEL_ID = "bridge_open_links"
        private const val ACTION_NOTIFICATION_ID_BASE = 2000
    }
}
