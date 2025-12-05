package com.light

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.d("BootReceiver", "Boot completed, checking if service should start")

            // Check if user has enabled background shake detection
            val sharedPreferences = context.getSharedPreferences(
                MainActivity.PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val isServiceEnabled = sharedPreferences.getBoolean(
                MainActivity.KEY_BACKGROUND_SERVICE,
                true
            )

            if (isServiceEnabled) {
                try {
                    // Start the shake detection service
                    val serviceIntent = Intent(context, ShakeDetectionService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d("BootReceiver", "ShakeDetectionService started after boot")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to start service after boot: ${e.message}", e)
                }
            } else {
                Log.d("BootReceiver", "Background service not enabled, not starting")
            }
        }
    }
}
