package com.light

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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

class MainActivity : AppCompatActivity() {

    private lateinit var flashlightButton: FloatingActionButton
    private lateinit var shareAppButton: FloatingActionButton
    private lateinit var strobeButton: FloatingActionButton
    private lateinit var backgroundServiceSwitch: SwitchMaterial
    private lateinit var timeoutSeekBar: SeekBar
    private lateinit var timeoutLabel: android.widget.TextView
    private lateinit var cameraManager: CameraManager
    private lateinit var sharedPreferences: SharedPreferences
    private var cameraId: String? = null
    private var isFlashlightOn = false
    private var isStrobeActive = false

    // Auto-off timeout
    private var timeoutMinutes = 0
    private val autoOffHandler = Handler(Looper.getMainLooper())
    private val autoOffRunnable = Runnable {
        if (isFlashlightOn) {
            toggleFlashlight()
            Toast.makeText(this, "Light auto-off timeout", Toast.LENGTH_SHORT).show()
        }
    }

    // Strobe mode
    private val strobeHandler = Handler(Looper.getMainLooper())
    private val strobeRunnable = object : Runnable {
        override fun run() {
            if (isStrobeActive) {
                try {
                    cameraId?.let { id ->
                        isFlashlightOn = !isFlashlightOn
                        cameraManager.setTorchMode(id, isFlashlightOn)
                    }
                    strobeHandler.postDelayed(this, 100) // Toggle every 100ms
                } catch (e: Exception) {
                    stopStrobe()
                }
            }
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 101
        const val PREFS_NAME = "LightPrefs"
        const val KEY_BACKGROUND_SERVICE = "background_service_enabled"
        const val KEY_TIMEOUT = "auto_off_timeout"
        const val DEFAULT_TIMEOUT = 3
        const val TIMEOUT_ACTION = "com.light.TIMEOUT_CHANGED"
        const val EXTRA_TIMEOUT = "timeout"
        const val SHAKE_THRESHOLD = 50f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        flashlightButton = findViewById(R.id.flashlightButton)
        shareAppButton = findViewById(R.id.shareAppButton)
        strobeButton = findViewById(R.id.strobeButton)
        backgroundServiceSwitch = findViewById(R.id.backgroundServiceSwitch)
        timeoutSeekBar = findViewById(R.id.timeoutSeekBar)
        timeoutLabel = findViewById(R.id.timeoutLabel)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        try {
            cameraId = cameraManager.cameraIdList[0]
        } catch (e: Exception) {
            Toast.makeText(this, "No camera found", Toast.LENGTH_SHORT).show()
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

        strobeButton.setOnClickListener {
            if (checkCameraPermission()) {
                toggleStrobe()
            } else {
                requestCameraPermission()
            }
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

    override fun onPause() {
        super.onPause()
        // Stop strobe when app goes to background
        if (isStrobeActive) {
            stopStrobe()
        }
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
        Toast.makeText(this, "Shake detection enabled", Toast.LENGTH_SHORT).show()
    }

    private fun stopBackgroundService() {
        val intent = Intent(this, ShakeDetectionService::class.java)
        stopService(intent)
        sharedPreferences.edit().putBoolean(KEY_BACKGROUND_SERVICE, false).apply()
        Toast.makeText(this, "Shake detection disabled", Toast.LENGTH_SHORT).show()
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
        // Stop strobe
        if (isStrobeActive) {
            stopStrobe()
        }
        // Turn off flashlight
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

    private fun toggleStrobe() {
        if (isStrobeActive) {
            stopStrobe()
        } else {
            startStrobe()
        }
    }

    private fun startStrobe() {
        // Stop normal flashlight if it's on
        if (isFlashlightOn) {
            toggleFlashlight()
        }

        isStrobeActive = true

        // Update strobe button appearance
        runOnUiThread {
            strobeButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.yellow)
        }

        // Start the strobe effect
        strobeHandler.post(strobeRunnable)

        Toast.makeText(this, "Strobe mode activated", Toast.LENGTH_SHORT).show()
    }

    private fun stopStrobe() {
        isStrobeActive = false
        strobeHandler.removeCallbacks(strobeRunnable)

        // Turn off flashlight and update button
        try {
            cameraId?.let { id ->
                cameraManager.setTorchMode(id, false)
                isFlashlightOn = false
            }

            runOnUiThread {
                strobeButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.orange)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error stopping strobe: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        Toast.makeText(this, "Strobe mode deactivated", Toast.LENGTH_SHORT).show()
    }
}
