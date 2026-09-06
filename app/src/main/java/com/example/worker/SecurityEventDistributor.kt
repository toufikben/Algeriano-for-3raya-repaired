package com.example.worker

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.SecurityPrefs
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

    fun enqueue(context: Context, eventId: String) {
        val prefs = SecurityPrefs.getInstance(context)
        if (prefs.getPendingSecurityEvents().none { it.id == eventId }) return

        val request = OneTimeWorkRequestBuilder<SecurityEventWorker>()
            .setInputData(workDataOf(EVENT_ID_KEY to eventId))
            .setInitialDelay(0L, TimeUnit.MILLISECONDS)
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
}

class SecurityEventWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val eventId = SecurityEventDistributor.eventId(inputData) ?: return Result.failure()
        val prefs = SecurityPrefs.getInstance(applicationContext)
        val event = prefs.getPendingSecurityEvents().firstOrNull { it.id == eventId }
            ?: return Result.success()

        return try {
            val intent = Intent(applicationContext, CameraForegroundService::class.java).apply {
                action = CameraForegroundService.ACTION_CAPTURE_AND_SEND
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
        SecurityEventDistributor.enqueuePending(applicationContext)
        if (prefs.hasPendingSecurityEvents()) {
            SecurityEventDistributor.scheduleRecovery(applicationContext)
        }
        return Result.success()
    }
}
