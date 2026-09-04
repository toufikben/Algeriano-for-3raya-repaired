package com.example.receiver

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.SecurityApp
import com.example.data.SecurityPrefs
import com.example.service.CameraForegroundService
import com.example.worker.CaptureRetryWorker

class CountdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = SecurityPrefs.getInstance(context)
        when (intent?.action) {
            CountdownScheduler.ACTION_WARNING -> showWarning(context, prefs)
            CountdownScheduler.ACTION_RESET -> {
                if (prefs.countdownEnabled && prefs.countdownEndTime > System.currentTimeMillis()) {
                    CountdownScheduler.start(context, prefs.countdownDurationMillis)
                }
            }
            CountdownScheduler.ACTION_EXPIRED -> {
                if (prefs.countdownEnabled && (prefs.countdownCapturePending || prefs.countdownEndTime <= System.currentTimeMillis())) {
                    prefs.countdownCapturePending = true
                    try {
                        val serviceIntent = Intent(context, CameraForegroundService::class.java).apply {
                            action = CameraForegroundService.ACTION_COUNTDOWN_EXPIRED
                        }
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } catch (e: Exception) {
                        CaptureRetryWorker.enqueue(context)
                    }
                }
            }
        }
    }

    private fun showWarning(context: Context, prefs: SecurityPrefs) {
        if (!prefs.countdownEnabled || prefs.countdownEndTime <= System.currentTimeMillis()) return

        val resetIntent = PendingIntent.getBroadcast(
            context,
            CountdownScheduler.RESET_REQUEST_CODE,
            Intent(context, CountdownReceiver::class.java).setAction(CountdownScheduler.ACTION_RESET),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            context,
            CountdownScheduler.OPEN_REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, SecurityApp.CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground_img_1787338860864)
            .setContentTitle("اقترب انتهاء مؤقت الحماية")
            .setContentText("تبقى أقل من 10 دقائق. اضغط لتمديد المؤقت إذا كنت تستخدم الهاتف.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("تبقى أقل من 10 دقائق على انتهاء مؤقت الحماية. إذا كنت تستخدم الهاتف، اضغط على تمديد المؤقت."))
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_launcher_foreground_img_1787338860864, "تمديد المؤقت", resetIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(CountdownScheduler.WARNING_NOTIFICATION_ID, notification)
    }
}

object CountdownScheduler {
    const val ACTION_WARNING = "com.example.action.COUNTDOWN_WARNING"
    const val ACTION_RESET = "com.example.action.COUNTDOWN_RESET"
    const val ACTION_EXPIRED = "com.example.action.COUNTDOWN_EXPIRED"
    const val RESET_REQUEST_CODE = 4102
    const val OPEN_REQUEST_CODE = 4103
    const val WARNING_NOTIFICATION_ID = 4104
    private const val WARNING_REQUEST_CODE = 4100
    private const val EXPIRED_REQUEST_CODE = 4101
    private const val WARNING_LEAD_MILLIS = 10 * 60 * 1000L

    fun start(context: Context, durationMillis: Long) {
        val prefs = SecurityPrefs.getInstance(context)
        val safeDuration = durationMillis.coerceAtLeast(60_000L)
        prefs.countdownDurationMillis = safeDuration
        prefs.countdownEndTime = System.currentTimeMillis() + safeDuration
        prefs.countdownCapturePending = false
        prefs.countdownRetryCount = 0
        prefs.countdownEnabled = true
        CaptureRetryWorker.cancel(context)
        context.getSystemService(NotificationManager::class.java)?.cancel(WARNING_NOTIFICATION_ID)
        scheduleAlarms(context, prefs.countdownEndTime)
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager?.cancel(pendingIntent(context, ACTION_WARNING, WARNING_REQUEST_CODE))
        alarmManager?.cancel(pendingIntent(context, ACTION_EXPIRED, EXPIRED_REQUEST_CODE))
        context.getSystemService(NotificationManager::class.java)?.cancel(WARNING_NOTIFICATION_ID)
        CaptureRetryWorker.cancel(context)
        SecurityPrefs.getInstance(context).clearCountdown()
    }

    fun rescheduleFromPrefs(context: Context) {
        val prefs = SecurityPrefs.getInstance(context)
        if (!prefs.countdownEnabled) return
        if (prefs.countdownCapturePending || prefs.countdownEndTime <= System.currentTimeMillis()) {
            prefs.countdownCapturePending = true
            CaptureRetryWorker.enqueue(context)
        } else {
            scheduleAlarms(context, prefs.countdownEndTime)
        }
    }

    fun schedulePendingRetry(context: Context) {
        CaptureRetryWorker.enqueue(context)
    }

    private fun scheduleAlarms(context: Context, endTime: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val warningAt = (endTime - WARNING_LEAD_MILLIS).coerceAtLeast(now + 1_000L)
        scheduleAlarm(context, alarmManager, warningAt, pendingIntent(context, ACTION_WARNING, WARNING_REQUEST_CODE))
        scheduleAlarm(context, alarmManager, endTime.coerceAtLeast(now + 1_000L), pendingIntent(context, ACTION_EXPIRED, EXPIRED_REQUEST_CODE))
    }

    private fun scheduleAlarm(context: Context, alarmManager: AlarmManager, triggerAt: Long, pendingIntent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CountdownReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
