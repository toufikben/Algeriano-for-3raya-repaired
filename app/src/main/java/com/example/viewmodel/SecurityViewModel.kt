package com.example.viewmodel

import android.app.ForegroundServiceStartNotAllowedException
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.IntruderLog
import com.example.data.SecurityPrefs
import com.example.receiver.CountdownScheduler
import com.example.receiver.MyDeviceAdminReceiver
import com.example.service.CameraForegroundService
import com.example.worker.SecurityEventDistributor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SecurityUiState(
    val email: String = "",
    val password: String = "",
    val isTrackingEnabled: Boolean = false,
    val isAdminActive: Boolean = false,
    val hasCameraPermission: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val isBatteryOptimizationIgnored: Boolean = false,
    val logs: List<IntruderLog> = emptyList(),
    val isTesting: Boolean = false,
    val saveFeedback: Boolean = false,
    val bannerMessage: String? = null,
    val countdownEnabled: Boolean = false,
    val countdownEndTime: Long = 0L,
    val countdownDurationMillis: Long = 60 * 60 * 1000L,
    val countdownRemainingMillis: Long = 0L
)

class SecurityViewModel(private val context: Context) : ViewModel() {

    private val prefs = SecurityPrefs.getInstance(context)

    private val _uiState = MutableStateFlow(
        SecurityUiState(
            email = prefs.email,
            password = prefs.password,
            isTrackingEnabled = prefs.isTrackingEnabled,
            logs = prefs.getLogs(),
            countdownEnabled = prefs.countdownEnabled,
            countdownEndTime = prefs.countdownEndTime,
            countdownDurationMillis = prefs.countdownDurationMillis,
            countdownRemainingMillis = remainingCountdownMillis()
        )
    )
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    init {
        val recoveredEvents = prefs.recoverStaleSecurityEvents()
        CountdownScheduler.rescheduleFromPrefs(context)
        if (recoveredEvents > 0) {
            _uiState.update {
                it.copy(bannerMessage = "تمت استعادة $recoveredEvents منبهات معلقة؛ راجع سجل المحاولات")
            }
        }
        SecurityEventDistributor.enqueuePending(context)
        viewModelScope.launch {
            prefs.trackingEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(isTrackingEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            prefs.logsFlow.collect { logList ->
                _uiState.update { it.copy(logs = logList) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _uiState.update {
                    it.copy(
                        countdownEnabled = prefs.countdownEnabled,
                        countdownEndTime = prefs.countdownEndTime,
                        countdownRemainingMillis = remainingCountdownMillis()
                    )
                }
            }
        }
        refreshStatuses()
    }

    private fun remainingCountdownMillis(): Long {
        if (!prefs.countdownEnabled) return 0L
        return (prefs.countdownEndTime - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun onEmailChange(newEmail: String) {
        _uiState.update { it.copy(email = newEmail) }
    }

    fun onPasswordChange(newPassword: String) {
        _uiState.update { it.copy(password = newPassword) }
    }

    fun saveCredentials() {
        val email = _uiState.value.email.trim()
        val password = _uiState.value.password.trim()
        prefs.email = email
        prefs.password = password

        viewModelScope.launch {
            _uiState.update { it.copy(saveFeedback = true) }
            delay(2000)
            _uiState.update { it.copy(saveFeedback = false) }
        }
    }

    fun toggleTracking(enabled: Boolean) {
        if (enabled && !_uiState.value.isAdminActive) {
            _uiState.update { it.copy(bannerMessage = "يرجى تفعيل صلاحية مدير الجهاز أولاً لتشغيل التتبع") }
            return
        }

        saveCredentials()
        prefs.isTrackingEnabled = enabled
        if (!enabled) {
            prefs.resetFailedUnlockAttempts()
            prefs.resetFailureAlertSession()
            CountdownScheduler.cancel(context)
        }
        _uiState.update { it.copy(isTrackingEnabled = enabled) }

        val serviceIntent = Intent(context, CameraForegroundService::class.java).apply {
            action = if (enabled) CameraForegroundService.ACTION_START_MONITORING else CameraForegroundService.ACTION_STOP_MONITORING
        }

        try {
            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                // Deliver the explicit stop action so the service can remove
                // its foreground and alert notifications before stopping.
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            val message = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException ->
                    "رفض النظام تشغيل خدمة الحماية من الخلفية؛ افتح التطبيق وشغّل الحماية من الواجهة"
                e is SecurityException ->
                    "لا يمكن تشغيل الخدمة؛ تحقق من صلاحيات الكاميرا والموقع"
                else -> "تعذر تشغيل خدمة الحماية"
            }
            _uiState.update { it.copy(bannerMessage = message, isTrackingEnabled = false) }
            prefs.isTrackingEnabled = false
        }
    }

    fun testAlert() {
        _uiState.update { it.copy(isTesting = true) }

        val serviceIntent = Intent(context, CameraForegroundService::class.java).apply {
            action = CameraForegroundService.ACTION_TEST_CAPTURE
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            val message = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException ->
                    "رفض النظام الاختبار من الخلفية؛ أعد المحاولة والتطبيق مفتوح"
                e is SecurityException ->
                    "لا يمكن اختبار الكاميرا؛ تحقق من صلاحية الكاميرا"
                else -> "تعذر تشغيل اختبار الكاميرا"
            }
            _uiState.update { it.copy(bannerMessage = message, isTesting = false) }
        }

        viewModelScope.launch {
            delay(4500)
            _uiState.update { it.copy(isTesting = false) }
        }
    }

    fun startCountdown(durationMillis: Long) {
        if (!_uiState.value.isTrackingEnabled) {
            _uiState.update { it.copy(bannerMessage = "يرجى تشغيل الحماية أولاً لتفعيل المؤقت") }
            return
        }
        CountdownScheduler.start(context, durationMillis)
        _uiState.update {
            it.copy(
                countdownEnabled = true,
                countdownEndTime = prefs.countdownEndTime,
                countdownDurationMillis = prefs.countdownDurationMillis,
                countdownRemainingMillis = remainingCountdownMillis()
            )
        }
    }

    fun cancelCountdown() {
        CountdownScheduler.cancel(context)
        _uiState.update {
            it.copy(countdownEnabled = false, countdownEndTime = 0L, countdownRemainingMillis = 0L)
        }
    }

    fun resetCountdown() {
        if (!_uiState.value.isTrackingEnabled) {
            _uiState.update { it.copy(bannerMessage = "يرجى تشغيل الحماية أولاً لإعادة تشغيل المؤقت") }
            return
        }
        CountdownScheduler.start(context, prefs.countdownDurationMillis)
        _uiState.update {
            it.copy(
                countdownEnabled = true,
                countdownEndTime = prefs.countdownEndTime,
                countdownDurationMillis = prefs.countdownDurationMillis,
                countdownRemainingMillis = remainingCountdownMillis(),
                bannerMessage = "تمت إعادة ضبط المؤقت بنفس المدة"
            )
        }
    }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.clearLogs()
        }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(bannerMessage = null) }
    }

    fun refreshStatuses() {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)
        val isAdmin = dpm?.isAdminActive(adminComponent) == true

        val hasCamera = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val hasLocation = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isBatteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            true
        }

        if (prefs.isTrackingEnabled && !isAdmin) {
            prefs.isTrackingEnabled = false
            prefs.resetFailedUnlockAttempts()
            CountdownScheduler.cancel(context)
            context.stopService(Intent(context, CameraForegroundService::class.java))
        }

        _uiState.update {
            it.copy(
                isAdminActive = isAdmin,
                hasCameraPermission = hasCamera,
                hasLocationPermission = hasLocation,
                hasNotificationPermission = hasNotifications,
                isBatteryOptimizationIgnored = isBatteryIgnored
            )
        }
    }
}
