package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.SecurityPrefs
import com.example.service.CameraForegroundService
import com.example.worker.SecurityEventDistributor

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "Received action: $action")

        if (Intent.ACTION_BOOT_COMPLETED == action || "android.intent.action.QUICKBOOT_POWERON" == action) {
            val prefs = SecurityPrefs.getInstance(context)
            CountdownScheduler.rescheduleFromPrefs(context)
            SecurityEventDistributor.enqueuePending(context)
            if (prefs.isTrackingEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Log.w(
                        "BootReceiver",
                        "Skipping camera foreground service start after boot on Android 14+; " +
                            "the user must start protection while the app is visible"
                    )
                    return
                }
                Log.d("BootReceiver", "Tracking is enabled, starting monitoring service")
                val serviceIntent = Intent(context, CameraForegroundService::class.java).apply {
                    this.action = CameraForegroundService.ACTION_START_MONITORING
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Could not start service on boot", e)
                }
            }
        }
    }
}
