package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.SecurityPrefs
import java.io.File
import java.util.concurrent.TimeUnit

/** Removes only old, unreferenced intruder photos from app-private storage. */
class PhotoCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val photoDirectory = applicationContext
            .getExternalFilesDir(null)
            ?.resolve(PHOTO_DIRECTORY)
            ?: return Result.success()

        val referencedPaths = buildSet {
            val prefs = SecurityPrefs.getInstance(applicationContext)
            prefs.getLogs().mapNotNullTo(this) { it.photoPath?.let(::canonicalPath) }
            prefs.getSecurityEvents().mapNotNullTo(this) { it.photoPath?.let(::canonicalPath) }
        }
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)

        photoDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff && canonicalPath(file) !in referencedPaths) {
                runCatching { file.delete() }
            }
        }
        return Result.success()
    }

    private fun canonicalPath(path: String): String? = runCatching {
        File(path).canonicalPath
    }.getOrNull()

    private fun canonicalPath(file: File): String = file.canonicalPath

    companion object {
        const val WORK_NAME = "weekly_photo_cleanup"
        private const val PHOTO_DIRECTORY = "intruder_photos"
        private const val RETENTION_DAYS = 7L
    }
}
