package com.light

import android.hardware.SensorEvent
import kotlin.math.sqrt

class ShakeDetector(
    private var threshold: Float,
    private val onShakeDetected: () -> Unit
) {
    private var lastShakeTime: Long = 0
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f

    companion object {
        private const val SHAKE_COOLDOWN = 1500L
    }

    fun updateThreshold(newThreshold: Float) {
        threshold = newThreshold
    }

    fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val currentTime = System.currentTimeMillis()

        // Skip first reading when last values are uninitialized
        if (lastX != 0f || lastY != 0f || lastZ != 0f) {
            if (currentTime - lastShakeTime > SHAKE_COOLDOWN) {
                val deltaX = x - lastX
                val deltaY = y - lastY
                val deltaZ = z - lastZ

                val acceleration = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)

                if (acceleration > threshold) {
                    lastShakeTime = currentTime
                    onShakeDetected()
                }
            }
        }

        lastX = x
        lastY = y
        lastZ = z
    }

    fun reset() {
        lastX = 0f
        lastY = 0f
        lastZ = 0f
        lastShakeTime = 0
    }
}
