package dev.asik.devicebridge.collectors

import android.content.Context
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.WindowManager
import dev.asik.devicebridge.hub.StreamHub
import dev.asik.devicebridge.model.TouchPointer
import dev.asik.devicebridge.model.TouchReading

class TouchCollector(
    context: Context,
    private val hub: StreamHub,
) {
    @Volatile
    private var isRunning = false

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun start() {
        isRunning = true
    }

    fun stop() {
        isRunning = false
    }

    fun onMotionEvent(event: MotionEvent, source: String = "app_ui") {
        if (!isRunning) return

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> "DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> "OTHER"
        }

        val pointers = mutableListOf<TouchPointer>()
        for (i in 0 until event.pointerCount) {
            val px = event.getX(i)
            val py = event.getY(i)
            val xNorm = if (screenW > 0) (px / screenW).coerceIn(0f, 1f) else 0f
            val yNorm = if (screenH > 0) (py / screenH).coerceIn(0f, 1f) else 0f

            pointers.add(
                TouchPointer(
                    id = event.getPointerId(i),
                    x = px,
                    y = py,
                    x_norm = xNorm,
                    y_norm = yNorm,
                    pressure = event.getPressure(i),
                    size = event.getSize(i),
                ),
            )
        }

        val reading = TouchReading(
            action = action,
            pointers = pointers,
            screen_width = screenW,
            screen_height = screenH,
            source = source,
        )

        hub.publishTouch(reading)
    }
}
