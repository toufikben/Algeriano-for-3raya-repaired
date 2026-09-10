package com.example.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
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
        if (failedAttempts < prefs.failedThreshold) {
            Log.d("DeviceAdminReceiver", "Failed unlock recorded ($failedAttempts/${prefs.failedThreshold})")
            return
        }

        // Every callback becomes an independent event. The active foreground
        // service serializes concurrent captures and the durable queue handles
        // events that cannot be processed immediately.
        val event = prefs.enqueueSecurityEvent()
        val captureIntent = Intent(context, CameraForegroundService::class.java).apply {
            action = CameraForegroundService.ACTION_CAPTURE_AND_SEND
            putExtra(CameraForegroundService.EXTRA_SECURITY_EVENT_ID, event.id)
        }
        try {
            // A plain startService() can throw IllegalStateException here on
            // API 26+: the app is virtually never in the foreground at the
            // moment someone else is failing to unlock the device. Route
            // through the compat helper so this, the primary trigger for the
            // whole app, uses the same background-start-safe path as every
            // other dispatch site (SecurityEventDistributor, SecurityEventWorker).
            ContextCompat.startForegroundService(context, captureIntent)
            Log.d("DeviceAdminReceiver", "Failed-unlock capture dispatched for event ${event.id}")
        } catch (e: Exception) {
            Log.e("DeviceAdminReceiver", "Direct capture dispatch failed; queued for recovery", e)
            SecurityEventDistributor.enqueue(context, event.id)
        }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        val prefs = SecurityPrefs.getInstance(context)
        // Reset the next unlock session without cancelling an event already
        // created for the preceding failed attempt; capture/email may still
        // be processing asynchronously.
        prefs.resetFailedUnlockAttempts(cancelPendingEvents = false)
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
