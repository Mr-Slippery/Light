package com.light

import android.hardware.SensorEvent
import kotlin.math.abs
import kotlin.math.sqrt

class ShakeDetector(
    private var threshold: Float,
    private val onShakeDetected: () -> Unit
) {
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastUpdateTime: Long = 0

    // Two-shake detection state
    private var firstShakeTime: Long = 0
    private var firstShakeDirection: ShakeDirection? = null
    private var shakeCount = 0

    // Peak detection
    private val accelerationHistory = mutableListOf<AccelerationData>()
    private var isPeakDetected = false
    private var lastPeakTime: Long = 0

    companion object {
        // Time window for the second shake (similar to Motorola: 500-800ms)
        private const val SECOND_SHAKE_WINDOW = 800L

        // Minimum time between peaks to avoid noise
        private const val MIN_PEAK_INTERVAL = 200L

        // Maximum time to consider as part of same shake gesture
        private const val SHAKE_TIMEOUT = 1500L

        // History buffer size for smoothing
        private const val HISTORY_SIZE = 5

        // Minimum angle consistency (dot product threshold)
        private const val DIRECTION_CONSISTENCY = 0.4f
    }

    data class AccelerationData(
        val x: Float,
        val y: Float,
        val z: Float,
        val magnitude: Float,
        val time: Long
    )

    data class ShakeDirection(
        val x: Float,
        val y: Float,
        val z: Float
    ) {
        fun dotProduct(other: ShakeDirection): Float {
            return x * other.x + y * other.y + z * other.z
        }

        fun magnitude(): Float {
            return sqrt(x * x + y * y + z * z)
        }

        fun normalize(): ShakeDirection {
            val mag = magnitude()
            return if (mag > 0) {
                ShakeDirection(x / mag, y / mag, z / mag)
            } else {
                this
            }
        }
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

        // Skip first reading
        if (lastUpdateTime == 0L) {
            lastX = x
            lastY = y
            lastZ = z
            lastUpdateTime = currentTime
            return
        }

        // Calculate delta (change in acceleration)
        val deltaX = x - lastX
        val deltaY = y - lastY
        val deltaZ = z - lastZ

        // Calculate acceleration magnitude (removing gravity by looking at change)
        val acceleration = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)

        // Add to history
        val accelData = AccelerationData(deltaX, deltaY, deltaZ, acceleration, currentTime)
        accelerationHistory.add(accelData)

        // Keep history limited
        if (accelerationHistory.size > HISTORY_SIZE) {
            accelerationHistory.removeAt(0)
        }

        if (acceleration > threshold && currentTime - lastPeakTime > MIN_PEAK_INTERVAL) {
            // Verify it's a real peak by checking if it's local maximum
            if (isPeak(accelData)) {
                handlePeak(accelData)
            }
        }

        // Reset if too much time has passed since first shake
        if (shakeCount > 0 && currentTime - firstShakeTime > SHAKE_TIMEOUT) {
            resetShakeDetection()
        }

        lastX = x
        lastY = y
        lastZ = z
        lastUpdateTime = currentTime
    }

    private fun isPeak(current: AccelerationData): Boolean {
        // Check if current acceleration is higher than recent history
        if (accelerationHistory.size < 3) return false

        val recentHistory = accelerationHistory.takeLast(3)
        val isLocalMax = recentHistory.all { it.magnitude <= current.magnitude }

        return isLocalMax
    }

    private fun handlePeak(peak: AccelerationData) {
        val currentTime = peak.time
        lastPeakTime = currentTime

        // Create direction vector from the peak
        val direction = ShakeDirection(peak.x, peak.y, peak.z).normalize()

        when (shakeCount) {
            0 -> {
                // First shake detected
                firstShakeTime = currentTime
                firstShakeDirection = direction
                shakeCount = 1
            }
            1 -> {
                // Check if this is the second shake within the time window
                val timeSinceFirst = currentTime - firstShakeTime

                if (timeSinceFirst in MIN_PEAK_INTERVAL..SECOND_SHAKE_WINDOW) {
                    // Check if direction is consistent with first shake
                    firstShakeDirection?.let { firstDir ->
                        val similarity = abs(firstDir.dotProduct(direction))

                        if (similarity >= DIRECTION_CONSISTENCY) {
                            // Valid two-shake gesture detected!
                            onShakeDetected()
                            resetShakeDetection()
                        } else {
                            // Direction doesn't match, reset and start fresh
                            resetShakeDetection()
                        }
                    }
                } else if (timeSinceFirst > SECOND_SHAKE_WINDOW) {
                    // Too slow, treat this as a new first shake
                    firstShakeTime = currentTime
                    firstShakeDirection = direction
                    shakeCount = 1
                }
            }
        }
    }

    private fun resetShakeDetection() {
        shakeCount = 0
        firstShakeTime = 0
        firstShakeDirection = null
    }

    fun reset() {
        lastX = 0f
        lastY = 0f
        lastZ = 0f
        lastUpdateTime = 0
        resetShakeDetection()
        accelerationHistory.clear()
        lastPeakTime = 0
    }
}
