package com.example.worker

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.SecurityPrefs
import com.example.service.CameraForegroundService
import java.util.concurrent.TimeUnit

class CaptureRetryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val prefs = SecurityPrefs.getInstance(applicationContext)
        prefs.recordCountdownDiagnostic("worker", "started", "attempt=$runAttemptCount")
        if (!prefs.countdownEnabled || !prefs.countdownCapturePending) {
            prefs.recordCountdownDiagnostic("worker", "ignored", "countdown_not_pending")
            return Result.success()
        }
        prefs.countdownRetryCount += 1
        prefs.recordCountdownDiagnostic("worker", "attempt", "count=${prefs.countdownRetryCount}")

        return try {
            val intent = Intent(applicationContext, CameraForegroundService::class.java).apply {
                action = CameraForegroundService.ACTION_COUNTDOWN_EXPIRED
                putExtra(CameraForegroundService.EXTRA_SECURITY_EVENT_ID, prefs.countdownEventId)
            }
            ContextCompat.startForegroundService(applicationContext, intent)
            prefs.recordCountdownDiagnostic("worker", "requested", "startForegroundService")
            Result.success()
        } catch (e: Exception) {
            val retry = runAttemptCount < MAX_WORK_RETRIES &&
                prefs.countdownRetryCount < MAX_CAPTURE_ATTEMPTS
            prefs.recordCountdownDiagnostic(
                "worker",
                if (retry) "retry" else "failed",
                "${e.javaClass.simpleName}:${e.message};count=${prefs.countdownRetryCount}"
            )
            if (retry) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val MAX_WORK_RETRIES = 2
        private const val UNIQUE_WORK_NAME = "countdown-capture-retry"
        private const val RETRY_DELAY_MINUTES = 1L

        fun enqueue(context: Context, replaceExisting: Boolean = false) {
            val prefs = SecurityPrefs.getInstance(context)
            if (!prefs.countdownEnabled || !prefs.countdownCapturePending) return
            if (prefs.countdownRetryCount >= MAX_CAPTURE_ATTEMPTS) return

            val request = OneTimeWorkRequestBuilder<CaptureRetryWorker>()
                .setInitialDelay(RETRY_DELAY_MINUTES, TimeUnit.MINUTES)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    RETRY_DELAY_MINUTES,
                    TimeUnit.MINUTES
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                if (replaceExisting) {
                    androidx.work.ExistingWorkPolicy.REPLACE
                } else {
                    androidx.work.ExistingWorkPolicy.KEEP
                },
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        const val MAX_CAPTURE_ATTEMPTS = 3
    }
}
