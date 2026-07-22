package dev.asik.devicebridge.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import dev.asik.devicebridge.BridgeRuntime
import dev.asik.devicebridge.model.TouchPointer
import dev.asik.devicebridge.model.TouchReading

/**
 * Transparent floating overlay using TYPE_APPLICATION_OVERLAY + FLAG_WATCH_OUTSIDE_TOUCH
 * to stream system-wide raw touch coordinates (rawX, rawY) across ALL apps without root!
 */
class TouchOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        setupOverlay()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            1,
            1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        val view = object : View(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                val actionStr = when (event.action) {
                    MotionEvent.ACTION_OUTSIDE, MotionEvent.ACTION_DOWN -> "DOWN"
                    MotionEvent.ACTION_MOVE -> "MOVE"
                    MotionEvent.ACTION_UP -> "UP"
                    else -> "TOUCH"
                }

                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay?.getRealMetrics(metrics)
                val screenW = metrics.widthPixels
                val screenH = metrics.heightPixels

                val rawX = event.rawX
                val rawY = event.rawY
                val xNorm = if (screenW > 0) (rawX / screenW).coerceIn(0f, 1f) else 0f
                val yNorm = if (screenH > 0) (rawY / screenH).coerceIn(0f, 1f) else 0f

                val pointer = TouchPointer(
                    id = 0,
                    x = rawX,
                    y = rawY,
                    x_norm = xNorm,
                    y_norm = yNorm,
                    pressure = event.pressure,
                    size = event.size,
                )

                val reading = TouchReading(
                    action = actionStr,
                    pointers = listOf(pointer),
                    screen_width = screenW,
                    screen_height = screenH,
                    source = "system_overlay",
                )

                BridgeRuntime.hub.publishTouch(reading)
                return false
            }
        }

        runCatching {
            windowManager?.addView(view, params)
            overlayView = view
        }
    }

    override fun onDestroy() {
        runCatching {
            overlayView?.let { windowManager?.removeView(it) }
        }
        overlayView = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)) {
                val intent = Intent(context, TouchOverlayService::class.java)
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TouchOverlayService::class.java)
            context.stopService(intent)
        }
    }
}
