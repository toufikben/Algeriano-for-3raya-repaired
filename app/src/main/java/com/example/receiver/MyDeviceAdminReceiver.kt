package com.example.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.SecurityPrefs
import com.example.service.CameraForegroundService
import com.example.worker.SecurityEventDistributor

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
            val event = prefs.enqueueSecurityEvent()
            // Protection starts CameraForegroundService while the app is visible.
            // Reuse that already-running FGS directly: Android 14+ blocks a
            // background WorkManager worker from starting a new camera FGS.
            val captureIntent = Intent(context, CameraForegroundService::class.java).apply {
                action = CameraForegroundService.ACTION_CAPTURE_AND_SEND
                putExtra(CameraForegroundService.EXTRA_SECURITY_EVENT_ID, event.id)
            }
            try {
                context.startService(captureIntent)
                Log.d("DeviceAdminReceiver", "Capture dispatched to persistent foreground service")
            } catch (e: Exception) {
                Log.e("DeviceAdminReceiver", "Direct capture dispatch failed; queued for recovery", e)
                SecurityEventDistributor.enqueue(context, event.id)
            }
        } else {
            Log.d("DeviceAdminReceiver", "Capture deferred until the threshold is reached")
        }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        val prefs = SecurityPrefs.getInstance(context)
        prefs.resetFailedUnlockAttempts()
        com.example.receiver.CountdownScheduler.cancel(context)
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
        com.example.receiver.CountdownScheduler.cancel(context)
    }
}
