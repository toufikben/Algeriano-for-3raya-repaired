package com.example.receiver

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.SecurityApp
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
            if (prefs.isTrackingEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ restricts camera foreground-service starts from
                    // BOOT_COMPLETED. Do not enqueue a Worker that would repeatedly
                    // attempt the same forbidden background start. Pending events
                    // remain durable and are enqueued when the user opens the app.
                    Log.w("BootReceiver", "Protection requires a visible app start after boot")
                    postResumeNotification(context)
                    return
                }
                SecurityEventDistributor.enqueuePending(context)
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
            } else {
                SecurityEventDistributor.enqueuePending(context)
            }
        }
    }

    private fun postResumeNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val openIntent = PendingIntent.getActivity(
            context,
            RESUME_REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val message = context.getString(R.string.notification_boot_text)
        val notification = NotificationCompat.Builder(context, SecurityApp.CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground_img_1787338860864)
            .setContentTitle(context.getString(R.string.notification_boot_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(RESUME_NOTIFICATION_ID, notification)
    }

    private companion object {
        const val RESUME_NOTIFICATION_ID = 4201
        const val RESUME_REQUEST_CODE = 4202
    }
}
