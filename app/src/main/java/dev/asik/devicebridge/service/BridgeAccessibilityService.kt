package dev.asik.devicebridge.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dev.asik.devicebridge.BridgeRuntime
import dev.asik.devicebridge.model.TouchPointer
import dev.asik.devicebridge.model.TouchReading

class BridgeAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val type = event.eventType
        val action = when (type) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICK"
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> "DOWN"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "MOVE"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "FOCUS"
            else -> "INTERACT"
        }

        val rect = Rect()
        val sourceNode = event.source
        sourceNode?.getBoundsInScreen(rect)

        if (!rect.isEmpty) {
            val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels

            val cx = rect.exactCenterX()
            val cy = rect.exactCenterY()
            val xNorm = if (screenW > 0) (cx / screenW).coerceIn(0f, 1f) else 0f
            val yNorm = if (screenH > 0) (cy / screenH).coerceIn(0f, 1f) else 0f

            val pointer = TouchPointer(
                id = 0,
                x = cx,
                y = cy,
                x_norm = xNorm,
                y_norm = yNorm,
            )

            val reading = TouchReading(
                action = action,
                pointers = listOf(pointer),
                screen_width = screenW,
                screen_height = screenH,
                source = "system_accessibility",
            )

            BridgeRuntime.hub.publishTouch(reading)
        }
    }

    override fun onInterrupt() {}
}
