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

    fun tap(x: Float, y: Float, onResult: ((Boolean) -> Unit)? = null) {
        val jitterX = x + Random.nextInt(-4, 5)
        val jitterY = y + Random.nextInt(-4, 5)

        val path = Path().apply {
            moveTo(jitterX.coerceAtLeast(1f), jitterY.coerceAtLeast(1f))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, Random.nextLong(55L, 85L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onResult?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onResult?.invoke(false)
            }
        }
        val dispatched = dispatchGesture(gesture, callback, null)
        if (!dispatched) {
            onResult?.invoke(false)
        }
    }
}
