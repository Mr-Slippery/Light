package com.light

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var flashlightButton: FloatingActionButton
    private lateinit var backgroundServiceSwitch: SwitchMaterial
    private lateinit var cameraManager: CameraManager
    private lateinit var sharedPreferences: SharedPreferences
    private var cameraId: String? = null
    private var isFlashlightOn = false

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 101
        private const val PREFS_NAME = "LightPrefs"
        private const val KEY_BACKGROUND_SERVICE = "background_service_enabled"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        flashlightButton = findViewById(R.id.flashlightButton)
        backgroundServiceSwitch = findViewById(R.id.backgroundServiceSwitch)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        try {
            cameraId = cameraManager.cameraIdList[0]
        } catch (e: Exception) {
            Toast.makeText(this, "No camera found", Toast.LENGTH_SHORT).show()
        }

        // Restore background service state
        val isServiceEnabled = sharedPreferences.getBoolean(KEY_BACKGROUND_SERVICE, false)
        backgroundServiceSwitch.isChecked = isServiceEnabled

        flashlightButton.setOnClickListener {
            if (checkCameraPermission()) {
                toggleFlashlight()
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
                if (isFlashlightOn) {
                    cameraManager.setTorchMode(id, false)
                    isFlashlightOn = false
                    flashlightButton.backgroundTintList =
                        ContextCompat.getColorStateList(this, android.R.color.white)
                } else {
                    cameraManager.setTorchMode(id, true)
                    isFlashlightOn = true
                    flashlightButton.backgroundTintList =
                        ContextCompat.getColorStateList(this, R.color.yellow)
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
}
