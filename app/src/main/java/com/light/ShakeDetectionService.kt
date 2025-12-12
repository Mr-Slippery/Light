package com.light

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class ShakeDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var cameraManager: CameraManager
    private lateinit var sharedPreferences: SharedPreferences
    private var accelerometer: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraId: String? = null
    private var isFlashlightOn = false

    // Shake detection
    private lateinit var shakeDetector: ShakeDetector

    // Auto-off timeout
    private var timeoutMinutes = 0
    private val autoOffHandler = Handler(Looper.getMainLooper())
    private val autoOffRunnable = Runnable {
        if (isFlashlightOn) {
            toggleFlashlight()
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ShakeDetectionChannelV2"
    }

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MainActivity.TIMEOUT_ACTION -> {
                    timeoutMinutes = intent.getIntExtra(MainActivity.EXTRA_TIMEOUT, 0)
                    if (isFlashlightOn) {
                        scheduleAutoOff()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        sharedPreferences = getSharedPreferences("LightPrefs", Context.MODE_PRIVATE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        shakeDetector = ShakeDetector(MainActivity.SHAKE_THRESHOLD) {
            toggleFlashlight()
        }

        timeoutMinutes = sharedPreferences.getInt(MainActivity.KEY_TIMEOUT, MainActivity.DEFAULT_TIMEOUT)

        android.util.Log.d("ShakeService", "Initialized with threshold=${MainActivity.SHAKE_THRESHOLD}, timeout=$timeoutMinutes")

        try {
            cameraId = cameraManager.cameraIdList[0]
        } catch (e: Exception) {
            // No camera available
        }

        val filter = IntentFilter().apply {
            addAction(MainActivity.TIMEOUT_ACTION)
        }
        ContextCompat.registerReceiver(
            this,
            settingsReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

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

        // Unregister broadcast receiver
        try {
            unregisterReceiver(settingsReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }

        // Cancel any pending auto-off
        autoOffHandler.removeCallbacks(autoOffRunnable)

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
            shakeDetector.onSensorChanged(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    private fun scheduleAutoOff() {
        // Cancel any existing timeout
        autoOffHandler.removeCallbacks(autoOffRunnable)

        // Schedule new timeout if enabled
        if (timeoutMinutes > 0) {
            autoOffHandler.postDelayed(autoOffRunnable, timeoutMinutes * 60 * 1000L)
        }
    }

    private fun toggleFlashlight() {
        try {
            cameraId?.let { id ->
                isFlashlightOn = !isFlashlightOn
                cameraManager.setTorchMode(id, isFlashlightOn)
                updateNotification()

                // Handle auto-off timeout
                if (isFlashlightOn) {
                    scheduleAutoOff()
                } else {
                    autoOffHandler.removeCallbacks(autoOffRunnable)
                }
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
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
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
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
