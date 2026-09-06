package com.example.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.SecurityPrefs
import com.example.receiver.CountdownScheduler
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
            SecurityEventDistributor.enqueue(context, event.id)
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
