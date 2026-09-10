package com.example.worker

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.SecurityPrefs
import com.example.data.SecurityEventStatus
import com.example.MainActivity
import com.example.R
import com.example.SecurityApp
import com.example.service.CameraForegroundService
import java.util.concurrent.TimeUnit

/**
 * Durable dispatcher for security events.
 *
 * It serializes each event by a unique WorkManager name. It does not claim an
 * event: the service claims it immediately before processing, which keeps a
 * worker crash from losing a PENDING event.
 */
object SecurityEventDistributor {
    private const val EVENT_ID_KEY = "security_event_id"
    private const val WORK_PREFIX = "security-event-"
    private const val RECOVERY_WORK_NAME = "security-events-recovery"
    private const val RECOVERY_DELAY_MINUTES = 2L
    private const val MAX_WORK_ATTEMPTS = 3
    private const val RETRY_DELAY_MINUTES = 1L

    fun enqueue(context: Context, eventId: String) {
        val prefs = SecurityPrefs.getInstance(context)
        if (prefs.getPendingSecurityEvents().none { it.id == eventId }) return

        val event = prefs.getPendingSecurityEvents().first { it.id == eventId }
        val retryDelayMinutes = if (event.sendAttempts == 0) {
            0L
        } else {
            RETRY_DELAY_MINUTES shl (event.sendAttempts - 1).coerceIn(0, 2)
        }
        val request = OneTimeWorkRequestBuilder<SecurityEventWorker>()
            .setInputData(workDataOf(EVENT_ID_KEY to eventId))
            .setInitialDelay(retryDelayMinutes, TimeUnit.MINUTES)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RETRY_DELAY_MINUTES,
                TimeUnit.MINUTES
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_PREFIX + eventId,
            ExistingWorkPolicy.KEEP,
            request
        )
        scheduleRecovery(context)
    }

    fun enqueuePending(context: Context) {
        SecurityPrefs.getInstance(context).getPendingSecurityEvents()
            .forEach { enqueue(context, it.id) }
    }

    /**
     * Rebuilds only reboot-safe work. Camera capture remains deferred until a
     * visible Activity starts it, while already-captured events can continue
     * through the durable send/retry path.
     */
    fun enqueueAfterReboot(context: Context) {
        val prefs = SecurityPrefs.getInstance(context)
        prefs.recoverStaleSecurityEvents()
        prefs.getPendingSecurityEvents()
            .filter { it.status == SecurityEventStatus.SEND_PENDING || it.status == SecurityEventStatus.FAILED_RETRYABLE }
            .forEach { enqueue(context, it.id) }
    }

    /** Dispatch durable events from a visible Activity instead of relying on a
     * background Worker to start a camera foreground service on Android 14+. */
    fun dispatchPendingFromVisibleContext(context: Context) {
        val appContext = context.applicationContext
        val prefs = SecurityPrefs.getInstance(appContext)
        prefs.getPendingSecurityEvents().forEach { event ->
            val countdownEvent = prefs.countdownCapturePending &&
                prefs.countdownEventId == event.id
            val intent = Intent(appContext, CameraForegroundService::class.java).apply {
                action = if (countdownEvent) {
                    CameraForegroundService.ACTION_COUNTDOWN_EXPIRED
                } else {
                    CameraForegroundService.ACTION_CAPTURE_AND_SEND
                }
                putExtra(CameraForegroundService.EXTRA_SECURITY_EVENT_ID, event.id)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(appContext, intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (_: Exception) {
                enqueue(appContext, event.id)
            }
        }
    }

    fun scheduleRecovery(context: Context) {
        val request = OneTimeWorkRequestBuilder<SecurityEventRecoveryWorker>()
            .setInitialDelay(RECOVERY_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            RECOVERY_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    internal fun eventId(input: androidx.work.Data): String? = input.getString(EVENT_ID_KEY)

    internal fun workName(eventId: String): String = WORK_PREFIX + eventId

    internal const val MAX_ATTEMPTS = MAX_WORK_ATTEMPTS

    fun notifyDeferredCapture(context: Context, eventId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val openIntent = PendingIntent.getActivity(
            context,
            eventId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, SecurityApp.CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground_img_1787338860864)
            .setContentTitle(context.getString(R.string.notification_deferred_title))
            .setContentText(context.getString(R.string.notification_deferred_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notification_deferred_text)))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(DEFERRED_NOTIFICATION_BASE + eventId.hashCode(), notification)
        }
    }

    private const val DEFERRED_NOTIFICATION_BASE = 31_000
}

class SecurityEventWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val eventId = SecurityEventDistributor.eventId(inputData) ?: return Result.failure()
        val prefs = SecurityPrefs.getInstance(applicationContext)
        val event = prefs.getPendingSecurityEvent(eventId)
            ?: return Result.success()

        // A camera foreground service must be started from a user-visible flow
        // on Android 14+. Keep the durable event pending; the next visible app
        // session will enqueue it again instead of converting this policy block
        // into a failed security event or an endless retry loop.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            event.status != SecurityEventStatus.SEND_PENDING &&
            event.status != SecurityEventStatus.FAILED_RETRYABLE
        ) {
            prefs.deferSecurityEvent(event.id)
            SecurityEventDistributor.notifyDeferredCapture(applicationContext, event.id)
            return Result.success()
        }

        return try {
            val intent = Intent(applicationContext, CameraForegroundService::class.java).apply {
                action = if (event.status == SecurityEventStatus.SEND_PENDING ||
                    event.status == SecurityEventStatus.FAILED_RETRYABLE
                ) {
                    CameraForegroundService.ACTION_SEND_PENDING
                } else {
                    CameraForegroundService.ACTION_CAPTURE_AND_SEND
                }
                putExtra(CameraForegroundService.EXTRA_SECURITY_EVENT_ID, event.id)
            }
            ContextCompat.startForegroundService(applicationContext, intent)
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount + 1 < SecurityEventDistributor.MAX_ATTEMPTS) {
                Result.retry()
            } else {
                prefs.failPendingSecurityEvent(event.id)
                Result.failure()
            }
        }
    }
}

class SecurityEventRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val prefs = SecurityPrefs.getInstance(applicationContext)
        prefs.recoverStaleSecurityEvents()
        prefs.getPendingSecurityEvents()
            .filter { it.status != com.example.data.SecurityEventStatus.DEFERRED }
            .forEach { SecurityEventDistributor.enqueue(applicationContext, it.id) }
        if (prefs.getPendingSecurityEvents().any { it.status != com.example.data.SecurityEventStatus.DEFERRED }) {
            SecurityEventDistributor.scheduleRecovery(applicationContext)
        }
        return Result.success()
    }
}
