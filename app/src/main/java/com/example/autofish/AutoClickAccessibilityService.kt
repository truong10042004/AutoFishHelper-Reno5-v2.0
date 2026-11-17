package com.example.autofish

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.random.Random

class AutoClickAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutoClickAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun tap(x: Float, y: Float, fastMode: Boolean = false) {
        val jitterX = x + Random.nextInt(-3, 4)
        val jitterY = y + Random.nextInt(-3, 4)

        val path = Path().apply {
            moveTo(jitterX.coerceAtLeast(1f), jitterY.coerceAtLeast(1f))
        }
        val duration = if (fastMode) Random.nextLong(16L, 26L) else Random.nextLong(35L, 52L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
