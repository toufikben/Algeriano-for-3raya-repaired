package com.example.worker

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.SecurityPrefs
import com.example.service.CameraForegroundService
import java.util.concurrent.TimeUnit

class CaptureRetryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val prefs = SecurityPrefs.getInstance(applicationContext)
        if (!prefs.countdownEnabled || !prefs.countdownCapturePending) return Result.success()

        return try {
            val intent = Intent(applicationContext, CameraForegroundService::class.java).apply {
                action = CameraForegroundService.ACTION_COUNTDOWN_EXPIRED
            }
            ContextCompat.startForegroundService(applicationContext, intent)
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < MAX_WORK_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val MAX_WORK_RETRIES = 2
        private const val UNIQUE_WORK_NAME = "countdown-capture-retry"
        private const val RETRY_DELAY_MINUTES = 1L

        fun enqueue(context: Context) {
            val prefs = SecurityPrefs.getInstance(context)
            if (!prefs.countdownEnabled || !prefs.countdownCapturePending) return
            if (prefs.countdownRetryCount >= MAX_CAPTURE_ATTEMPTS) return

            prefs.countdownRetryCount += 1
            val request = OneTimeWorkRequestBuilder<CaptureRetryWorker>()
                .setInitialDelay(RETRY_DELAY_MINUTES, TimeUnit.MINUTES)
                .setInputData(workDataOf("retry_number" to prefs.countdownRetryCount))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        const val MAX_CAPTURE_ATTEMPTS = 3
    }
}
