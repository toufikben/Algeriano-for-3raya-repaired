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
        prefs.recordCountdownDiagnostic(
            stage = "receiver",
            status = "received",
            detail = intent?.action ?: "null_action"
        )
        when (intent?.action) {
            CountdownScheduler.ACTION_WARNING -> {
                prefs.recordCountdownDiagnostic("warning", "started", "warning_broadcast")
                showWarning(context, prefs)
            }
            CountdownScheduler.ACTION_RESET -> {
                if (prefs.countdownEnabled && prefs.countdownEndTime > System.currentTimeMillis()) {
                    prefs.recordCountdownDiagnostic("reset", "accepted", "remaining_countdown_reset")
                    CountdownScheduler.start(context, prefs.countdownDurationMillis)
                } else {
                    prefs.recordCountdownDiagnostic("reset", "ignored", "countdown_not_active_or_expired")
                }
            }
            CountdownScheduler.ACTION_EXPIRED -> {
                if (prefs.countdownEnabled && (prefs.countdownCapturePending || prefs.countdownEndTime <= System.currentTimeMillis())) {
                    prefs.recordCountdownDiagnostic("expired", "accepted", "expiration_broadcast")
                    prefs.countdownCapturePending = true
                    if (prefs.countdownEventId.isBlank()) {
                        prefs.countdownEventId = prefs.enqueueSecurityEvent().id
                    }
                    try {
                        val serviceIntent = Intent(context, CameraForegroundService::class.java).apply {
                            action = CameraForegroundService.ACTION_COUNTDOWN_EXPIRED
                            putExtra(CameraForegroundService.EXTRA_SECURITY_EVENT_ID, prefs.countdownEventId)
                        }
                        // The monitoring service is normally already in the
                        // foreground. Reuse it first; only request a new FGS
                        // start when Android reports that it is not running.
                        try {
                            context.startService(serviceIntent)
                            prefs.recordCountdownDiagnostic("service_start", "requested", "startService")
                        } catch (_: Exception) {
                            try {
                                ContextCompat.startForegroundService(context, serviceIntent)
                                prefs.recordCountdownDiagnostic("service_start", "requested", "startForegroundService_fallback")
                            } catch (e: Exception) {
                                prefs.recordCountdownDiagnostic("service_start", "failed", "${e.javaClass.simpleName}:${e.message}")
                                throw e
                            }
                        }
                    } catch (e: Exception) {
                        prefs.recordCountdownDiagnostic("retry", "scheduled", "receiver_service_start:${e.javaClass.simpleName}")
                        CaptureRetryWorker.enqueue(context)
                    }
                } else {
                    prefs.recordCountdownDiagnostic("expired", "ignored", "countdown_not_active")
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
            .setContentTitle(context.getString(com.example.R.string.ui_158bb9c625fb))
            .setContentText(context.getString(com.example.R.string.ui_0b183fd63f65))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(com.example.R.string.ui_226510ac7ba6)))
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_launcher_foreground_img_1787338860864, context.getString(com.example.R.string.ui_771471017b22), resetIntent)
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
        prefs.countdownEventId = ""
        prefs.countdownEnabled = true
        CaptureRetryWorker.cancel(context)
        context.getSystemService(NotificationManager::class.java)?.cancel(WARNING_NOTIFICATION_ID)
        prefs.recordCountdownDiagnostic("schedule", "requested", "duration_ms=$safeDuration")
        scheduleAlarms(context, prefs.countdownEndTime)
    }

    fun cancel(context: Context) {
        SecurityPrefs.getInstance(context).recordCountdownDiagnostic("schedule", "cancelled", "user_or_protection_stop")
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager?.cancel(pendingIntent(context, ACTION_WARNING, WARNING_REQUEST_CODE))
        alarmManager?.cancel(pendingIntent(context, ACTION_EXPIRED, EXPIRED_REQUEST_CODE))
        context.getSystemService(NotificationManager::class.java)?.cancel(WARNING_NOTIFICATION_ID)
        CaptureRetryWorker.cancel(context)
        SecurityPrefs.getInstance(context).clearCountdown()
    }

    fun rescheduleFromPrefs(context: Context) {
        val prefs = SecurityPrefs.getInstance(context)
        if (!prefs.countdownEnabled) {
            prefs.recordCountdownDiagnostic("reschedule", "ignored", "countdown_disabled")
            return
        }
        if (prefs.countdownCapturePending || prefs.countdownEndTime <= System.currentTimeMillis()) {
            prefs.recordCountdownDiagnostic("reschedule", "retry", "expired_or_capture_pending")
            prefs.countdownCapturePending = true
            CaptureRetryWorker.enqueue(context)
        } else {
            prefs.recordCountdownDiagnostic("reschedule", "requested", "active_countdown")
            scheduleAlarms(context, prefs.countdownEndTime)
        }
    }

    fun schedulePendingRetry(context: Context) {
        SecurityPrefs.getInstance(context).recordCountdownDiagnostic("retry", "requested", "capture_pending")
        CaptureRetryWorker.enqueue(context)
    }

    private fun scheduleAlarms(context: Context, endTime: Long) {
        val prefs = SecurityPrefs.getInstance(context)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (alarmManager == null) {
            prefs.recordCountdownDiagnostic("schedule", "failed", "alarm_manager_unavailable")
            return
        }
        val now = System.currentTimeMillis()
        val warningAt = (endTime - WARNING_LEAD_MILLIS).coerceAtLeast(now + 1_000L)
        runCatching {
            scheduleAlarm(alarmManager, warningAt, pendingIntent(context, ACTION_WARNING, WARNING_REQUEST_CODE), prefs, "warning")
        }
        runCatching {
            scheduleAlarm(alarmManager, endTime.coerceAtLeast(now + 1_000L), pendingIntent(context, ACTION_EXPIRED, EXPIRED_REQUEST_CODE), prefs, "expired")
        }
    }

    private fun scheduleAlarm(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent,
        prefs: SecurityPrefs,
        kind: String
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                prefs.recordCountdownDiagnostic("schedule", "success", "$kind:exact_allow_while_idle")
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                prefs.recordCountdownDiagnostic("schedule", "success", "$kind:allow_while_idle")
            }
        } catch (e: SecurityException) {
            prefs.recordCountdownDiagnostic("schedule", "fallback", "$kind:exact_security_exception:${e.message}")
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                prefs.recordCountdownDiagnostic("schedule", "success", "$kind:fallback_allow_while_idle")
            } catch (fallback: Exception) {
                prefs.recordCountdownDiagnostic("schedule", "failed", "$kind:${fallback.javaClass.simpleName}:${fallback.message}")
                throw fallback
            }
        } catch (e: Exception) {
            prefs.recordCountdownDiagnostic("schedule", "failed", "$kind:${e.javaClass.simpleName}:${e.message}")
            throw e
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
