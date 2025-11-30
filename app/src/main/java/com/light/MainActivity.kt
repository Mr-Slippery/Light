package com.light

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var flashlightButton: FloatingActionButton
    private lateinit var shareAppButton: FloatingActionButton
    private lateinit var backgroundServiceSwitch: SwitchMaterial
    private lateinit var sensitivitySeekBar: SeekBar
    private lateinit var sensitivityLabel: android.widget.TextView
    private lateinit var timeoutSeekBar: SeekBar
    private lateinit var timeoutLabel: android.widget.TextView
    private lateinit var cameraManager: CameraManager
    private lateinit var sensorManager: SensorManager
    private lateinit var sharedPreferences: SharedPreferences
    private var accelerometer: Sensor? = null
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
            Toast.makeText(this, "Light auto-off timeout", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 101
        const val PREFS_NAME = "LightPrefs"
        const val KEY_BACKGROUND_SERVICE = "background_service_enabled"
        const val KEY_SENSITIVITY = "shake_sensitivity"
        const val KEY_TIMEOUT = "auto_off_timeout"
        const val DEFAULT_SENSITIVITY = 20 // 20 on 0-50 scale
        const val DEFAULT_TIMEOUT = 3 // 3 minutes auto-off
        const val SENSITIVITY_ACTION = "com.light.SENSITIVITY_CHANGED"
        const val EXTRA_SENSITIVITY = "sensitivity"
        const val TIMEOUT_ACTION = "com.light.TIMEOUT_CHANGED"
        const val EXTRA_TIMEOUT = "timeout"

        // Convert SeekBar progress (0-50) to threshold (75-10)
        // Progress 0 = threshold 75 (low sensitivity, requires hard shake)
        // Progress 25 = threshold 42.5 (moderate)
        // Progress 50 = threshold 10 (high sensitivity, very sensitive, light shake works)
        fun progressToThreshold(progress: Int): Float {
            return 75f - (progress.toFloat() * 1.3f)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        flashlightButton = findViewById(R.id.flashlightButton)
        shareAppButton = findViewById(R.id.shareAppButton)
        backgroundServiceSwitch = findViewById(R.id.backgroundServiceSwitch)
        sensitivitySeekBar = findViewById(R.id.sensitivitySeekBar)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)
        timeoutSeekBar = findViewById(R.id.timeoutSeekBar)
        timeoutLabel = findViewById(R.id.timeoutLabel)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        try {
            cameraId = cameraManager.cameraIdList[0]
        } catch (e: Exception) {
            Toast.makeText(this, "No camera found", Toast.LENGTH_SHORT).show()
        }

        // Initialize shake detector
        val savedSensitivity = sharedPreferences.getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)
        shakeDetector = ShakeDetector(progressToThreshold(savedSensitivity)) {
            if (checkCameraPermission()) {
                toggleFlashlight()
            }
        }

        // Restore background service state (default to true for first run)
        val isFirstRun = !sharedPreferences.contains(KEY_BACKGROUND_SERVICE)
        val isServiceEnabled = sharedPreferences.getBoolean(KEY_BACKGROUND_SERVICE, true)
        backgroundServiceSwitch.isChecked = isServiceEnabled

        // Start service if enabled (including first run)
        if (isServiceEnabled) {
            if (checkCameraPermission() && checkNotificationPermission()) {
                startBackgroundService()
            } else if (isFirstRun) {
                // On first run, request permissions if needed
                if (!checkCameraPermission()) {
                    requestCameraPermission()
                } else if (!checkNotificationPermission()) {
                    requestNotificationPermission()
                }
            }
        }

        // Restore sensitivity setting
        sensitivitySeekBar.progress = savedSensitivity
        updateSensitivityLabel()

        // Restore timeout setting
        timeoutMinutes = sharedPreferences.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT)
        timeoutSeekBar.progress = timeoutMinutes
        updateTimeoutLabel()

        flashlightButton.setOnClickListener {
            if (checkCameraPermission()) {
                toggleFlashlight()
            } else {
                requestCameraPermission()
            }
        }

        shareAppButton.setOnClickListener {
            shareApp()
        }

        backgroundServiceSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (checkCameraPermission()) {
                    if (checkNotificationPermission()) {
                        startBackgroundService()
                    } else {
                        requestNotificationPermission()
                        backgroundServiceSwitch.isChecked = false
                    }
                } else {
                    requestCameraPermission()
                    backgroundServiceSwitch.isChecked = false
                }
            } else {
                stopBackgroundService()
            }
        }

        sensitivitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val threshold = progressToThreshold(progress)
                    sharedPreferences.edit().putInt(KEY_SENSITIVITY, progress).apply()
                    shakeDetector.updateThreshold(threshold)
                    updateSensitivityLabel()
                    // Notify service of sensitivity change
                    val intent = Intent(SENSITIVITY_ACTION)
                    intent.setPackage(packageName) // Make broadcast explicit
                    intent.putExtra(EXTRA_SENSITIVITY, threshold)
                    sendBroadcast(intent)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        timeoutSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    timeoutMinutes = progress
                    sharedPreferences.edit().putInt(KEY_TIMEOUT, progress).apply()
                    updateTimeoutLabel()
                    // Notify service of timeout change
                    val intent = Intent(TIMEOUT_ACTION)
                    intent.setPackage(packageName) // Make broadcast explicit
                    intent.putExtra(EXTRA_TIMEOUT, progress)
                    sendBroadcast(intent)
                    // If light is currently on, restart the timer
                    if (isFlashlightOn) {
                        scheduleAutoOff()
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateSensitivityLabel() {
        // Display inverted value: when threshold is low (10), show high sensitivity (75)
        // when threshold is high (75), show low sensitivity (10)
        val threshold = progressToThreshold(sensitivitySeekBar.progress)
        val displayValue = (85 - threshold).toInt()
        sensitivityLabel.text = "Shake Sensitivity: $displayValue"
    }

    private fun updateTimeoutLabel() {
        val text = if (timeoutMinutes == 0) {
            "Auto-off: Never"
        } else {
            "Auto-off: ${timeoutMinutes}min"
        }
        timeoutLabel.text = text
    }

    private fun scheduleAutoOff() {
        // Cancel any existing timeout
        autoOffHandler.removeCallbacks(autoOffRunnable)

        // Schedule new timeout if enabled
        if (timeoutMinutes > 0) {
            autoOffHandler.postDelayed(autoOffRunnable, timeoutMinutes * 60 * 1000L)
        }
    }

    override fun onResume() {
        super.onResume()
        // Register sensor listener when app is in foreground
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister sensor listener when app goes to background
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            shakeDetector.onSensorChanged(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required before Android 13
        }
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted
                } else {
                    Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
                }
            }
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startBackgroundService()
                    backgroundServiceSwitch.isChecked = true
                } else {
                    Toast.makeText(this, "Notification permission is required for background service", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startBackgroundService() {
        val intent = Intent(this, ShakeDetectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        sharedPreferences.edit().putBoolean(KEY_BACKGROUND_SERVICE, true).apply()
        Toast.makeText(this, "Background shake detection enabled", Toast.LENGTH_SHORT).show()
    }

    private fun stopBackgroundService() {
        val intent = Intent(this, ShakeDetectionService::class.java)
        stopService(intent)
        sharedPreferences.edit().putBoolean(KEY_BACKGROUND_SERVICE, false).apply()
        Toast.makeText(this, "Background shake detection disabled", Toast.LENGTH_SHORT).show()
    }

    private fun toggleFlashlight() {
        try {
            cameraId?.let { id ->
                isFlashlightOn = !isFlashlightOn
                cameraManager.setTorchMode(id, isFlashlightOn)

                // Update UI on main thread
                runOnUiThread {
                    flashlightButton.backgroundTintList = if (isFlashlightOn) {
                        ContextCompat.getColorStateList(this, R.color.yellow)
                    } else {
                        ContextCompat.getColorStateList(this, android.R.color.white)
                    }
                }

                // Handle auto-off timeout
                if (isFlashlightOn) {
                    scheduleAutoOff()
                } else {
                    autoOffHandler.removeCallbacks(autoOffRunnable)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error toggling flashlight: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFlashlightOn) {
            try {
                cameraId?.let { cameraManager.setTorchMode(it, false) }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun shareApp() {
        try {
            // Get the APK file path
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val apkPath = packageInfo.applicationInfo.sourceDir
            val apkFile = File(apkPath)

            // Create a temporary file in cache directory
            val cacheDir = File(cacheDir, "apk")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            val sharedApk = File(cacheDir, "Light.apk")

            // Copy APK to cache directory
            FileInputStream(apkFile).use { input ->
                FileOutputStream(sharedApk).use { output ->
                    input.copyTo(output)
                }
            }

            // Get URI using FileProvider
            val apkUri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                sharedApk
            )

            // Create share intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, apkUri)
                putExtra(Intent.EXTRA_SUBJECT, "Light - Flashlight App")
                putExtra(Intent.EXTRA_TEXT, "Check out this awesome flashlight app!")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Share Light app via"))

        } catch (e: Exception) {
            Toast.makeText(this, "Error sharing app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
