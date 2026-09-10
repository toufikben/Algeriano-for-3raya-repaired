package com.example

import com.example.R

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.i18n.LanguageStore
import com.example.worker.PhotoCleanupWorker
import java.util.Locale
import java.util.concurrent.TimeUnit

class SecurityApp : Application() {

    companion object {
        const val CHANNEL_ID_SERVICE = "security_service_channel"
        const val CHANNEL_ID_ALERTS = "security_alerts_channel"
    }

    override fun attachBaseContext(base: Context) {
        val language = LanguageStore(base).get()
        val locale = Locale.forLanguageTag(language.code)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        super.attachBaseContext(base.createConfigurationContext(configuration))
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        schedulePhotoCleanup()
    }

    private fun schedulePhotoCleanup() {
        val request = PeriodicWorkRequestBuilder<PhotoCleanupWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(7, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PhotoCleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }

            val alertsChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                getString(com.example.R.string.ui_ba80f4ab2a31),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(com.example.R.string.ui_3b035380cc10)
                enableVibration(true)
                setShowBadge(true)
            }

            manager?.createNotificationChannel(serviceChannel)
            manager?.createNotificationChannel(alertsChannel)
        }
    }
}
