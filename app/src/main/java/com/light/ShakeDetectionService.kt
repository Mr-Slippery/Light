package com.light

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class ShakeDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var cameraManager: CameraManager
    private var accelerometer: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraId: String? = null
    private var isFlashlightOn = false

    // Shake detection variables
    private var lastShakeTime: Long = 0
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ShakeDetectionChannel"
        private const val SHAKE_THRESHOLD = 25f
        private const val SHAKE_COOLDOWN = 1500L
    }

    override fun onCreate() {
        super.onCreate()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        try {
            cameraId = cameraManager.cameraIdList[0]
        } catch (e: Exception) {
            // No camera available
        }

        // Acquire wake lock to keep CPU running for sensor detection
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Light::ShakeDetectionWakeLock"
        )
        wakeLock?.acquire()

        // Register sensor listener
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        // Unregister sensor listener
        sensorManager.unregisterListener(this)

        // Turn off flashlight if on
        if (isFlashlightOn) {
            try {
                cameraId?.let { cameraManager.setTorchMode(it, false) }
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val currentTime = System.currentTimeMillis()

            if (currentTime - lastShakeTime > SHAKE_COOLDOWN) {
                val deltaX = x - lastX
                val deltaY = y - lastY
                val deltaZ = z - lastZ

                val acceleration = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)

                if (acceleration > SHAKE_THRESHOLD) {
                    lastShakeTime = currentTime
                    toggleFlashlight()
                }
            }

            lastX = x
            lastY = y
            lastZ = z
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    private fun toggleFlashlight() {
        try {
            cameraId?.let { id ->
                isFlashlightOn = !isFlashlightOn
                cameraManager.setTorchMode(id, isFlashlightOn)
                updateNotification()
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shake Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps shake detection active in background"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isFlashlightOn) "Light is ON" else "Listening for shake"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Light Shake Detection Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
