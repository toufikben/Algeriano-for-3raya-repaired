package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.GCMParameterSpec
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import android.security.keystore.KeyPermanentlyInvalidatedException

data class IntruderLog(
    val id: String,
    val eventId: String? = null,
    val photoCaptured: Boolean = false,
    val locationCaptured: Boolean = false,
    val timestamp: Long,
    val photoPath: String?,
    val latitude: Double?,
    val longitude: Double?,
    val address: String?,
    val emailSent: Boolean,
    val statusMessage: String
)

enum class SecurityEventStatus {
    PENDING, IN_PROGRESS, CAPTURED, SEND_PENDING, SENT,
    FAILED, FAILED_RETRYABLE, FAILED_FINAL, CANCELLED
}

data class SecurityEvent(
    val id: String,
    val timestamp: Long,
    val failedAttempt: Int,
    val status: SecurityEventStatus,
    val updatedAt: Long = timestamp,
    val recoveryAttempts: Int = 0,
    val sendAttempts: Int = 0,
    val photoPath: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationTimestamp: Long? = null
)

class SecurityPrefs private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val store = SecurityStore(context)

    init {
        migrateLegacyStorageIfNeeded()
    }

    private val _trackingEnabledFlow = MutableStateFlow(isTrackingEnabled)
    val trackingEnabledFlow: StateFlow<Boolean> = _trackingEnabledFlow.asStateFlow()

    private val _logsFlow = MutableStateFlow(getLogs())
    val logsFlow: StateFlow<List<IntruderLog>> = _logsFlow.asStateFlow()

    companion object {
        private const val PREF_NAME = "intruder_security_prefs"
        private const val KEY_EMAIL = "key_email"
        private const val KEY_PASSWORD = "key_password"
        private const val KEY_TRACKING_ENABLED = "key_tracking_enabled"
        private const val KEY_TOTAL_ATTEMPTS = "key_total_attempts"
        private const val KEY_LAST_ATTEMPT_TIME = "key_last_attempt_time"
        private const val KEY_LAST_ATTEMPT_LOCATION = "key_last_attempt_location"
        private const val KEY_LOGS_JSON = "key_logs_json"
        private const val KEY_THRESHOLD = "key_failed_threshold"
        private const val KEY_FAILED_UNLOCK_ATTEMPTS = "key_failed_unlock_attempts"
        private const val KEY_FAILURE_ALERT_SENT = "key_failure_alert_sent"
        private const val KEY_COUNTDOWN_ENABLED = "key_countdown_enabled"
        private const val KEY_COUNTDOWN_END_TIME = "key_countdown_end_time"
        private const val KEY_COUNTDOWN_DURATION = "key_countdown_duration"
        private const val KEY_COUNTDOWN_CAPTURE_PENDING = "key_countdown_capture_pending"
        private const val KEY_COUNTDOWN_RETRY_COUNT = "key_countdown_retry_count"
        private const val KEY_APP_PIN_SALT = "key_app_pin_salt"
        private const val KEY_APP_PIN_HASH = "key_app_pin_hash"
        private const val KEY_PIN_FAILED_ATTEMPTS = "key_pin_failed_attempts"
        private const val KEY_PIN_BLOCKED_UNTIL = "key_pin_blocked_until"
        private const val KEY_SECURITY_EVENTS_JSON = "key_security_events_json"
        private const val KEYSTORE_ALIAS = "intruder_security_credentials"
        private const val ENCRYPTED_PREFIX = "v1:"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val MAX_SECURITY_EVENTS = 100
        private const val EVENT_LEASE_TIMEOUT_MILLIS = 2 * 60 * 1000L
        private const val MAX_EVENT_RECOVERY_ATTEMPTS = 2
        private const val MAX_SEND_ATTEMPTS = 3
        private const val PIN_ITERATIONS = 120_000
        private const val PIN_KEY_LENGTH = 256
        private const val PIN_MIN_LENGTH = 6
        private const val PIN_MAX_LENGTH = 8
        private const val PIN_LOCKOUT_THRESHOLD = 5
        private const val PIN_LOCKOUT_MILLIS = 30_000L
        private const val DEFAULT_COUNTDOWN_DURATION_MILLIS = 60 * 60 * 1000L

        @Volatile
        private var INSTANCE: SecurityPrefs? = null

        fun getInstance(context: Context): SecurityPrefs {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurityPrefs(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    var email: String
        get() = readSecret(KEY_EMAIL)
        set(value) = writeSecret(KEY_EMAIL, value.trim())

    var password: String
        get() = readSecret(KEY_PASSWORD)
        set(value) = writeSecret(KEY_PASSWORD, value.trim())

    private fun readSecret(key: String): String {
        val stored = prefs.getString(key, "") ?: return ""
        if (stored.isBlank()) return ""
        if (!stored.startsWith(ENCRYPTED_PREFIX)) {
            // One-time migration for credentials saved by older app versions.
            writeSecret(key, stored)
            return stored
        }

        return try {
            decrypt(stored.removePrefix(ENCRYPTED_PREFIX))
        } catch (e: Exception) {
            Log.e("SecurityPrefs", "Unable to decrypt stored credential: ${e.javaClass.simpleName}")
            ""
        }
    }

    private fun writeSecret(key: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(key).apply()
            return
        }

        try {
            val encrypted = ENCRYPTED_PREFIX + encrypt(value)
            prefs.edit().putString(key, encrypted).apply()
        } catch (e: Exception) {
            Log.e("SecurityPrefs", "Unable to encrypt credential: ${e.javaClass.simpleName}")
        }
    }

    private fun getOrCreateCredentialKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        } catch (_: KeyPermanentlyInvalidatedException) {
            // A restored or changed device lock can invalidate the old key.
            // Delete only the unusable key; callers will receive an empty secret
            // rather than crashing or falling back to plaintext storage.
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        }

        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateCredentialKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(
            ByteBuffer.allocate(4 + iv.size + ciphertext.size)
                .putInt(iv.size)
                .put(iv)
                .put(ciphertext)
                .array(),
            Base64.NO_WRAP
        )
    }

    private fun decrypt(encoded: String): String {
        val payload = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
        val ivLength = payload.int
        require(ivLength == GCM_IV_LENGTH_BYTES) { "Invalid credential IV" }
        val iv = ByteArray(ivLength)
        payload.get(iv)
        val ciphertext = ByteArray(payload.remaining())
        payload.get(ciphertext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateCredentialKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    var isTrackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TRACKING_ENABLED, value).apply()
            _trackingEnabledFlow.value = value
        }

    var totalAttempts: Int
        get() = prefs.getInt(KEY_TOTAL_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_TOTAL_ATTEMPTS, value).apply()

    var lastAttemptTime: Long
        get() = prefs.getLong(KEY_LAST_ATTEMPT_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ATTEMPT_TIME, value).apply()

    var lastAttemptLocation: String
        get() = prefs.getString(KEY_LAST_ATTEMPT_LOCATION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_ATTEMPT_LOCATION, value).apply()

    var failedThreshold: Int
        get() = prefs.getInt(KEY_THRESHOLD, 3).coerceAtLeast(3)
        set(value) = prefs.edit().putInt(KEY_THRESHOLD, value.coerceAtLeast(3)).apply()

    /** Returns true only for the first failed-unlock callback in a session. */
    @Synchronized
    fun beginFailureAlertSession(): Boolean {
        if (prefs.getBoolean(KEY_FAILURE_ALERT_SENT, false)) return false
        prefs.edit().putBoolean(KEY_FAILURE_ALERT_SENT, true).apply()
        return true
    }

    @Synchronized
    fun resetFailureAlertSession() {
        prefs.edit().putBoolean(KEY_FAILURE_ALERT_SENT, false).apply()
    }

    /**
     * Records one failed unlock attempt and returns the consecutive total
     * since the last successful unlock.
     */
    @Synchronized
    fun registerFailedUnlockAttempt(): Int {
        val attempts = prefs.getInt(KEY_FAILED_UNLOCK_ATTEMPTS, 0) + 1
        prefs.edit().putInt(KEY_FAILED_UNLOCK_ATTEMPTS, attempts).apply()
        return attempts
    }

    /** Resets the consecutive failed-unlock counter after a successful unlock. */
    @Synchronized
    fun resetFailedUnlockAttempts(cancelPendingEvents: Boolean = true) {
        prefs.edit().putInt(KEY_FAILED_UNLOCK_ATTEMPTS, 0).apply()
        if (!cancelPendingEvents) return
        updateSecurityEventsInRoom { events ->
            events.map { event ->
                if (event.status == SecurityEventStatus.PENDING ||
                    event.status == SecurityEventStatus.IN_PROGRESS ||
                    event.status == SecurityEventStatus.SEND_PENDING ||
                    event.status == SecurityEventStatus.FAILED_RETRYABLE
                ) event.copy(status = SecurityEventStatus.CANCELLED) else event
            }.takeLast(MAX_SECURITY_EVENTS)
        }
    }

    @Synchronized
    fun enqueueSecurityEvent(timestamp: Long = System.currentTimeMillis()): SecurityEvent {
        val event = SecurityEvent(
            id = UUID.randomUUID().toString(),
            timestamp = timestamp,
            failedAttempt = prefs.getInt(KEY_FAILED_UNLOCK_ATTEMPTS, 0),
            status = SecurityEventStatus.PENDING,
            updatedAt = timestamp
        )
        updateSecurityEventsInRoom { (it + event).takeLast(MAX_SECURITY_EVENTS) }
        return event
    }

    @Synchronized
    fun claimNextSecurityEvent(): SecurityEvent? {
        val next = getSecurityEventsFromRoom().firstOrNull { it.status == SecurityEventStatus.PENDING } ?: return null
        updateSecurityEventsInRoom { events ->
            events.map {
                if (it.id == next.id) it.copy(
                    status = SecurityEventStatus.IN_PROGRESS,
                    updatedAt = System.currentTimeMillis()
                ) else it
            }
        }
        return next.copy(status = SecurityEventStatus.IN_PROGRESS, updatedAt = System.currentTimeMillis())
    }

    @Synchronized
    fun claimSecurityEvent(id: String): Boolean {
        val event = getSecurityEventsFromRoom().firstOrNull { it.id == id } ?: return false
        if (event.status != SecurityEventStatus.PENDING) return false
        updateSecurityEventsInRoom { events ->
            events.map {
                if (it.id == id) it.copy(
                    status = SecurityEventStatus.IN_PROGRESS,
                    updatedAt = System.currentTimeMillis()
                ) else it
            }
        }
        return true
    }

    @Synchronized
    fun completeSecurityEvent(id: String, status: SecurityEventStatus) {
        updateSecurityEventsInRoom { events ->
            events.map {
                if (it.id == id && SecurityEventStateMachine.canTransition(it.status, status)) {
                    it.copy(status = status, updatedAt = System.currentTimeMillis())
                } else {
                    it
                }
            }
        }
    }

    @Synchronized
    fun getSecurityEvent(id: String): SecurityEvent? = getSecurityEventsFromRoom().firstOrNull { it.id == id }

    @Synchronized
    fun recordCaptureResult(
        id: String,
        photoPath: String?,
        latitude: Double?,
        longitude: Double?,
        locationTimestamp: Long?
    ) {
        updateSecurityEventsInRoom { events ->
            events.map {
                if (it.id == id && it.status == SecurityEventStatus.IN_PROGRESS) {
                    it.copy(
                        status = SecurityEventStatus.SEND_PENDING,
                        updatedAt = System.currentTimeMillis(),
                        photoPath = photoPath,
                        latitude = latitude,
                        longitude = longitude,
                        locationTimestamp = locationTimestamp
                    )
                } else it
            }
        }
    }

    @Synchronized
    fun recordSendRetry(id: String): Boolean {
        var retry = false
        updateSecurityEventsInRoom { events ->
            events.map {
                if (it.id == id && it.status == SecurityEventStatus.SEND_PENDING) {
                    val attempts = it.sendAttempts + 1
                    retry = attempts < MAX_SEND_ATTEMPTS
                    it.copy(
                        status = if (retry) SecurityEventStatus.FAILED_RETRYABLE else SecurityEventStatus.FAILED_FINAL,
                        sendAttempts = attempts,
                        updatedAt = System.currentTimeMillis()
                    )
                } else it
            }
        }
        return retry
    }

    @Synchronized
    fun markSendPendingForRetry(id: String): Boolean {
        var changed = false
        updateSecurityEventsInRoom { events ->
            events.map {
                if (it.id == id && SecurityEventStateMachine.canTransition(
                        it.status,
                        SecurityEventStatus.SEND_PENDING
                    )) {
                    changed = true
                    it.copy(status = SecurityEventStatus.SEND_PENDING, updatedAt = System.currentTimeMillis())
                } else it
            }
        }
        return changed
    }

    @Synchronized
    fun failPendingSecurityEvent(id: String) {
        updateSecurityEventsInRoom { events ->
            events.map {
                if (it.id == id && SecurityEventStateMachine.canTransition(
                        it.status,
                        SecurityEventStatus.FAILED
                    )) {
                    it.copy(status = SecurityEventStatus.FAILED, updatedAt = System.currentTimeMillis())
                } else it
            }
        }
    }

    fun hasPendingSecurityEvents(): Boolean = synchronized(this) {
        getSecurityEventsFromRoom().any {
            it.status == SecurityEventStatus.PENDING ||
                it.status == SecurityEventStatus.IN_PROGRESS ||
                it.status == SecurityEventStatus.SEND_PENDING ||
                it.status == SecurityEventStatus.FAILED_RETRYABLE
        }
    }

    fun getPendingSecurityEvents(): List<SecurityEvent> = synchronized(this) {
        getSecurityEventsFromRoom().filter {
            it.status == SecurityEventStatus.PENDING ||
                it.status == SecurityEventStatus.SEND_PENDING ||
                it.status == SecurityEventStatus.FAILED_RETRYABLE
        }
    }

    @Synchronized
    fun getPendingSecurityEvent(id: String): SecurityEvent? {
        return store.event(id)?.takeIf {
            it.status == SecurityEventStatus.PENDING ||
                it.status == SecurityEventStatus.SEND_PENDING ||
                it.status == SecurityEventStatus.FAILED_RETRYABLE
        }
    }

    /** Re-queues abandoned work after a process/device restart, with a bounded retry count. */
    @Synchronized
    fun recoverStaleSecurityEvents(now: Long = System.currentTimeMillis()): Int {
        var recovered = 0
        updateSecurityEventsInRoom { events ->
            events.map { event ->
                val stale = (event.status == SecurityEventStatus.IN_PROGRESS ||
                    event.status == SecurityEventStatus.SEND_PENDING) &&
                    now - event.updatedAt >= EVENT_LEASE_TIMEOUT_MILLIS
                if (!stale) return@map event

                if (event.recoveryAttempts < MAX_EVENT_RECOVERY_ATTEMPTS) {
                    recovered += 1
                    event.copy(
                        status = if (event.photoPath != null) SecurityEventStatus.SEND_PENDING
                            else SecurityEventStatus.PENDING,
                        updatedAt = now,
                        recoveryAttempts = event.recoveryAttempts + 1
                    )
                } else {
                    event.copy(status = SecurityEventStatus.FAILED_FINAL, updatedAt = now)
                }
            }
        }
        return recovered
    }

    private fun readLegacySecurityEvents(): List<SecurityEvent> {
        val json = prefs.getString(KEY_SECURITY_EVENTS_JSON, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                SecurityEvent(
                    id = item.getString("id"),
                    timestamp = item.getLong("timestamp"),
                    failedAttempt = item.getInt("failedAttempt"),
                    status = runCatching {
                        SecurityEventStatus.valueOf(item.getString("status"))
                    }.getOrDefault(SecurityEventStatus.FAILED),
                    updatedAt = item.optLong("updatedAt", item.optLong("timestamp")),
                    recoveryAttempts = item.optInt("recoveryAttempts", 0).coerceAtLeast(0),
                    sendAttempts = item.optInt("sendAttempts", 0).coerceAtLeast(0),
                    photoPath = if (item.has("photoPath")) item.getString("photoPath") else null,
                    latitude = if (item.has("latitude")) item.optDouble("latitude") else null,
                    longitude = if (item.has("longitude")) item.optDouble("longitude") else null,
                    locationTimestamp = if (item.has("locationTimestamp")) item.optLong("locationTimestamp") else null
                )
            }
        } catch (e: Exception) {
            Log.e("SecurityPrefs", "Unable to read security events", e)
            emptyList()
        }
    }

    private fun getSecurityEventsFromRoom(): List<SecurityEvent> = store.events()

    private fun updateSecurityEventsInRoom(transform: (List<SecurityEvent>) -> List<SecurityEvent>) {
        store.replaceEvents(transform(store.events()).takeLast(MAX_SECURITY_EVENTS))
    }

    private fun migrateLegacyStorageIfNeeded() {
        val legacyEvents = readLegacySecurityEvents()
        val legacyLogs = readLegacyLogs()
        if (store.events().isEmpty() && legacyEvents.isNotEmpty()) store.replaceEvents(legacyEvents)
        if (store.logs().isEmpty() && legacyLogs.isNotEmpty()) store.replaceLogs(legacyLogs)
        if (legacyEvents.isNotEmpty() || legacyLogs.isNotEmpty()) {
            prefs.edit().remove(KEY_SECURITY_EVENTS_JSON).remove(KEY_LOGS_JSON).apply()
        }
    }

    val hasAppPin: Boolean
        get() = prefs.getString(KEY_APP_PIN_SALT, null).isNullOrBlank().not() &&
            prefs.getString(KEY_APP_PIN_HASH, null).isNullOrBlank().not()

    fun setAppPin(pin: String): Boolean {
        if (!pin.matches(Regex("\\d{$PIN_MIN_LENGTH,$PIN_MAX_LENGTH}"))) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derivePinHash(pin, salt)
        prefs.edit()
            .putString(KEY_APP_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_APP_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
        return true
    }

    fun verifyAppPin(pin: String): Boolean {
        // Legacy four- or five-digit PINs remain verifiable so users can migrate.
        if (!hasAppPin || !pin.matches(Regex("\\d{4,$PIN_MAX_LENGTH}"))) return false
        return try {
            val salt = Base64.decode(prefs.getString(KEY_APP_PIN_SALT, ""), Base64.NO_WRAP)
            val expected = Base64.decode(prefs.getString(KEY_APP_PIN_HASH, ""), Base64.NO_WRAP)
            java.security.MessageDigest.isEqual(derivePinHash(pin, salt), expected)
        } catch (_: Exception) {
            false
        }
    }

    fun changeAppPin(currentPin: String, newPin: String): Boolean {
        if (!verifyAppPin(currentPin)) return false
        return setAppPin(newPin)
    }

    fun isPinBlocked(now: Long = System.currentTimeMillis()): Boolean =
        prefs.getLong(KEY_PIN_BLOCKED_UNTIL, 0L) > now

    fun getPinBlockedUntil(): Long = prefs.getLong(KEY_PIN_BLOCKED_UNTIL, 0L)

    @Synchronized
    fun registerPinFailure(now: Long = System.currentTimeMillis()): Long {
        if (isPinBlocked(now)) return prefs.getLong(KEY_PIN_BLOCKED_UNTIL, 0L)
        val attempts = prefs.getInt(KEY_PIN_FAILED_ATTEMPTS, 0) + 1
        return if (attempts >= PIN_LOCKOUT_THRESHOLD) {
            val blockedUntil = now + PIN_LOCKOUT_MILLIS
            prefs.edit().putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
                .putLong(KEY_PIN_BLOCKED_UNTIL, blockedUntil).apply()
            blockedUntil
        } else {
            prefs.edit().putInt(KEY_PIN_FAILED_ATTEMPTS, attempts).apply()
            0L
        }
    }

    @Synchronized
    fun resetPinFailures() {
        prefs.edit().putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
            .putLong(KEY_PIN_BLOCKED_UNTIL, 0L).apply()
    }

    private fun derivePinHash(pin: String, salt: ByteArray): ByteArray {
        val spec: KeySpec = PBEKeySpec(pin.toCharArray(), salt, PIN_ITERATIONS, PIN_KEY_LENGTH)
        val factory = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        } catch (_: java.security.NoSuchAlgorithmException) {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        }
        return factory.generateSecret(spec).encoded
    }

    var countdownEnabled: Boolean
        get() = prefs.getBoolean(KEY_COUNTDOWN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_COUNTDOWN_ENABLED, value).apply()

    var countdownEndTime: Long
        get() = prefs.getLong(KEY_COUNTDOWN_END_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_COUNTDOWN_END_TIME, value).apply()

    var countdownDurationMillis: Long
        get() = prefs.getLong(KEY_COUNTDOWN_DURATION, DEFAULT_COUNTDOWN_DURATION_MILLIS)
        set(value) = prefs.edit().putLong(KEY_COUNTDOWN_DURATION, value).apply()

    var countdownCapturePending: Boolean
        get() = prefs.getBoolean(KEY_COUNTDOWN_CAPTURE_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_COUNTDOWN_CAPTURE_PENDING, value).apply()

    var countdownRetryCount: Int
        get() = prefs.getInt(KEY_COUNTDOWN_RETRY_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_COUNTDOWN_RETRY_COUNT, value.coerceAtLeast(0)).apply()

    fun clearCountdown() {
        prefs.edit()
            .putBoolean(KEY_COUNTDOWN_ENABLED, false)
            .putLong(KEY_COUNTDOWN_END_TIME, 0L)
            .putBoolean(KEY_COUNTDOWN_CAPTURE_PENDING, false)
            .putInt(KEY_COUNTDOWN_RETRY_COUNT, 0)
            .apply()
    }

    @Synchronized
    fun addLog(log: IntruderLog) {
        val currentLogs = getLogs().toMutableList()
        val existingIndex = log.eventId?.let { eventId ->
            currentLogs.indexOfFirst { it.eventId == eventId }
        } ?: -1
        val isNewLog = existingIndex < 0
        if (isNewLog) {
            currentLogs.add(0, log)
        } else {
            currentLogs[existingIndex] = log.copy(id = currentLogs[existingIndex].id)
        }
        // Keep max 50 logs
        val trimmed = if (currentLogs.size > 50) currentLogs.take(50) else currentLogs
        saveLogs(trimmed)
        if (isNewLog) {
            totalAttempts += 1
            lastAttemptTime = log.timestamp
            if (log.latitude != null && log.longitude != null) {
                lastAttemptLocation = "${log.latitude}, ${log.longitude}"
            }
        }
        _logsFlow.value = trimmed
    }

    private fun readLegacyLogs(): List<IntruderLog> {
        val jsonString = prefs.getString(KEY_LOGS_JSON, "[]") ?: "[]"
        val list = mutableListOf<IntruderLog>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    IntruderLog(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        eventId = obj.optString("eventId").takeIf { it.isNotEmpty() },
                        photoCaptured = if (obj.has("photoCaptured")) {
                            obj.optBoolean("photoCaptured")
                        } else {
                            !obj.optString("photoPath").isNullOrEmpty()
                        },
                        locationCaptured = if (obj.has("locationCaptured")) {
                            obj.optBoolean("locationCaptured")
                        } else {
                            obj.has("latitude") && obj.has("longitude")
                        },
                        timestamp = obj.optLong("timestamp", 0L),
                        photoPath = obj.optString("photoPath").takeIf { it.isNotEmpty() },
                        latitude = if (obj.has("latitude")) obj.optDouble("latitude") else null,
                        longitude = if (obj.has("longitude")) obj.optDouble("longitude") else null,
                        address = obj.optString("address").takeIf { it.isNotEmpty() },
                        emailSent = obj.optBoolean("emailSent", false),
                        statusMessage = obj.optString("statusMessage", "")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("SecurityPrefs", "Unable to read intruder logs", e)
        }
        return list
    }

    private fun saveLogs(logs: List<IntruderLog>) = store.replaceLogs(logs)

    fun getLogs(): List<IntruderLog> = store.logs()

    fun clearLogs() {
        // Optionally delete image files
        val logs = getLogs()
        for (log in logs) {
            log.photoPath?.let { path ->
                try {
                    val file = File(path)
                    if (file.exists()) file.delete()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
        saveLogs(emptyList())
        totalAttempts = 0
        _logsFlow.value = emptyList()
    }

}
