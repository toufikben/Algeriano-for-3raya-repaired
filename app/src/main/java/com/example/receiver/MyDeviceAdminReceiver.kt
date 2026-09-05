package com.example.receiver

import android.app.admin.DeviceAdminReceiver
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.SecurityPrefs
import com.example.receiver.CountdownScheduler
import com.example.service.CameraForegroundService

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)

        val prefs = SecurityPrefs.getInstance(context)
        if (!prefs.isTrackingEnabled) {
            Log.d("DeviceAdminReceiver", "Password attempt ignored because protection is disabled")
            return
        }

        val failedAttempts = prefs.registerFailedUnlockAttempt()
        val threshold = prefs.failedThreshold
        Log.d(
            "DeviceAdminReceiver",
            "Password attempt failed: $failedAttempts/$threshold consecutive attempts"
        )

        if (failedAttempts >= threshold) {
            val serviceIntent = Intent(context, CameraForegroundService::class.java).apply {
                action = CameraForegroundService.ACTION_CAPTURE_AND_SEND
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                ) {
                    Log.e(
                        "DeviceAdminReceiver",
                        "System rejected foreground service start while app is in background",
                        e
                    )
                } else {
                    Log.e("DeviceAdminReceiver", "Failed to start foreground service", e)
                }
            }
        } else {
            Log.d("DeviceAdminReceiver", "Capture deferred until the threshold is reached")
        }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        val prefs = SecurityPrefs.getInstance(context)
        prefs.resetFailedUnlockAttempts()
        CountdownScheduler.cancel(context)
        Log.d("DeviceAdminReceiver", "Password succeeded; consecutive failed attempts reset")
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d("DeviceAdminReceiver", "Device Admin Enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d("DeviceAdminReceiver", "Device Admin Disabled")
        // If disabled, turn off tracking to avoid inconsistent UI
        val prefs = SecurityPrefs.getInstance(context)
        prefs.isTrackingEnabled = false
        prefs.resetFailedUnlockAttempts()
        CountdownScheduler.cancel(context)
    }
}
