package com.example.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.SecurityApp
import com.example.data.IntruderLog
import com.example.data.SecurityPrefs
import com.example.data.SecurityEventStatus
import com.example.data.SecurityEventComponentState
import com.example.receiver.CountdownScheduler
import com.example.util.EmailSender
import com.example.worker.CaptureRetryWorker
import com.example.worker.SecurityEventDistributor
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class CameraForegroundService : Service() {

    companion object {
        const val ACTION_CAPTURE_AND_SEND = "com.example.action.CAPTURE_AND_SEND"
        const val ACTION_SEND_PENDING = "com.example.action.SEND_PENDING"
        const val ACTION_COUNTDOWN_EXPIRED = "com.example.action.COUNTDOWN_EXPIRED"
        const val ACTION_START_MONITORING = "com.example.action.START_MONITORING"
        const val ACTION_TEST_CAPTURE = "com.example.action.TEST_CAPTURE"
        const val ACTION_STOP_MONITORING = "com.example.action.STOP_MONITORING"
        const val EXTRA_SECURITY_EVENT_ID = "extra_security_event_id"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 2002
        private const val TAG = "CameraService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val captureInProgress = AtomicBoolean(false)
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var foregroundStarted = false
    private var foregroundNeedsCamera = true

    override fun onCreate() {
        super.onCreate()
        val prefs = SecurityPrefs.getInstance(applicationContext)
        prefs.recordCountdownDiagnostic("service", "created", "foreground_service_on_create")
        prefs.recoverStaleSecurityEvents()
        startBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_CAPTURE_AND_SEND
        foregroundNeedsCamera = action != ACTION_SEND_PENDING
        Log.d(TAG, "onStartCommand action: $action")

        if (action == ACTION_STOP_MONITORING) {
            NotificationManagerCompat.from(this).cancel(ALERT_NOTIFICATION_ID)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
            return START_NOT_STICKY
        }

        // Protection is event-driven. Do not keep an idle camera foreground
        // service alive; Android 14 may stop or reject that pattern.
        if (action == ACTION_START_MONITORING) {
            Log.d(TAG, "Ignoring idle monitoring request; captures are event-driven")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!foregroundStarted) {
            if (!promoteToForeground(buildForegroundNotification())) {
                val prefs = SecurityPrefs.getInstance(applicationContext)
                prefs.recordCountdownDiagnostic(
                    "service",
                    "failed",
                    "promote_to_foreground_failed"
                )
                if (action == ACTION_COUNTDOWN_EXPIRED) {
                    prefs.countdownCapturePending = true
                    CaptureRetryWorker.enqueue(applicationContext, replaceExisting = true)
                } else if (action == ACTION_CAPTURE_AND_SEND) {
                    // FIX: device-admin events must not be dropped when FGS
                    // promotion fails (background start denied, permission).
                    // Leave event PENDING and hand it to the durable queue.
                    val pendingId = intent?.getStringExtra(EXTRA_SECURITY_EVENT_ID)
                    if (!pendingId.isNullOrBlank()) {
                        prefs.deferSecurityEvent(pendingId)
                        com.example.worker.SecurityEventDistributor.enqueue(applicationContext, pendingId)
                    }
                }
                stopSelf(startId)
                return START_NOT_STICKY
            }
            SecurityPrefs.getInstance(applicationContext).recordCountdownDiagnostic(
                "service",
                "ready",
                "foreground_service_started"
            )
            foregroundStarted = true
        }

        when (action) {
            ACTION_CAPTURE_AND_SEND, ACTION_SEND_PENDING, ACTION_COUNTDOWN_EXPIRED, ACTION_TEST_CAPTURE -> {
                val isTest = action == ACTION_TEST_CAPTURE
                val eventId = intent?.getStringExtra(EXTRA_SECURITY_EVENT_ID)
                processIntruderCapture(isTest, action == ACTION_COUNTDOWN_EXPIRED, eventId)
            }
            ACTION_START_MONITORING -> Unit
        }

        return START_NOT_STICKY
    }

    private fun promoteToForeground(notification: Notification): Boolean {
        val hasCameraPermission = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        // FIX: allow location/email-only capture when CAMERA is missing.
        // Previously we returned false here and dropped the whole event.
        if (!hasCameraPermission) {
            Log.w(TAG, "Starting foreground service without CAMERA permission (photo will be skipped)")
        }

        val hasLocationPermission = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (if (hasCameraPermission && foregroundNeedsCamera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) or
                if (hasLocationPermission) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                }
        } else {
            0
        }

        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                foregroundServiceType
            )
            true
        } catch (e: Exception) {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException -> {
                    Log.e(TAG, "Foreground service start not allowed by the system", e)
                }
                e is SecurityException -> {
                    Log.e(TAG, "Foreground service permission or while-in-use access is missing", e)
                }
                else -> Log.e(TAG, "Unable to promote service to foreground", e)
            }
            false
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SecurityApp.CHANNEL_ID_SERVICE)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground_img_1787338860864)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun processIntruderCapture(
        isTest: Boolean,
        isCountdownCapture: Boolean = false,
        eventId: String? = null
    ) {
        val prefs = SecurityPrefs.getInstance(applicationContext)
        if (!isTest && eventId.isNullOrBlank()) {
            Log.e(TAG, "Ignoring capture request without a security event id")
            if (isCountdownCapture) {
                prefs.countdownCapturePending = true
                CaptureRetryWorker.enqueue(applicationContext, replaceExisting = true)
            }
            return
        }
        val existingEvent = eventId?.let { prefs.getSecurityEvent(it) }
        val sendOnly = !isTest && existingEvent?.status in setOf(
            SecurityEventStatus.CAPTURED,
            SecurityEventStatus.SEND_PENDING,
            SecurityEventStatus.FAILED_RETRYABLE
        ) && !existingEvent?.photoPath.isNullOrBlank()
        if (!isTest && !eventId.isNullOrBlank() && !sendOnly && !prefs.claimSecurityEvent(eventId)) {
            Log.w(TAG, "Ignoring duplicate or already-processed security event: $eventId")
            return
        }
        if (!captureInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Capture already in progress; ignoring duplicate request")
            // FIX: do not downgrade SEND_PENDING/FAILED_RETRYABLE to PENDING
            // and do not spawn unbounded waiter coroutines. The event already
            // claimed stays IN_PROGRESS and will be processed; duplicates are
            // dropped. Countdown retry is still scheduled once.
            if (isCountdownCapture) {
                CountdownScheduler.schedulePendingRetry(applicationContext)
            }
            return
        }

        if (!isTest && eventId != null && !sendOnly) {
            prefs.markCapturePending(eventId)
        }

        // Schedule the next countdown before touching camera, location, or email.
        // A failure in any of those operations must not stop an auto-restarting timer.
        val autoRestartCountdown = isCountdownCapture &&
            prefs.countdownAutoRestart &&
            prefs.isTrackingEnabled
        val capturedCountdownEndTime = if (isCountdownCapture) prefs.countdownEndTime else 0L
        if (autoRestartCountdown) {
            CountdownScheduler.start(applicationContext, prefs.countdownDurationMillis)
            prefs.recordCountdownDiagnostic("capture", "success", "auto_restart_scheduled_before_capture")
            Log.d(TAG, "Countdown completed; next cycle scheduled before capture")
        }

        val wakeLock = acquireWakeLock()

        serviceScope.launch {
            var logSaved = false
            try {
                val timestamp = System.currentTimeMillis()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val timeStr = dateFormat.format(Date(timestamp))

                // 1–2. Start the camera and location requests together. A
                // retry reuses the saved photo/coordinates and does not start
                // either hardware operation again.
                val (capturedFile, location) = coroutineScope {
                    val photoDeferred = async(Dispatchers.IO) {
                        if (sendOnly) {
                            existingEvent?.photoPath?.let(::File)?.takeIf { it.exists() }
                        } else {
                            captureImageSilently()
                        }
                    }
                    val locationDeferred = async(Dispatchers.IO) {
                        if (sendOnly && existingEvent?.latitude != null && existingEvent.longitude != null) {
                            Location("saved-event").apply {
                                latitude = existingEvent.latitude
                                longitude = existingEvent.longitude
                                time = existingEvent.locationTimestamp ?: existingEvent.timestamp
                            }
                        } else {
                            fetchCurrentLocation()
                        }
                    }
                    photoDeferred.await() to locationDeferred.await()
                }
                val cameraPermissionGranted = ActivityCompat.checkSelfPermission(
                    this@CameraForegroundService,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                val locationPermissionGranted = ActivityCompat.checkSelfPermission(
                    this@CameraForegroundService,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                    this@CameraForegroundService,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                val lat = location?.latitude
                val lng = location?.longitude
                val photoCaptured = capturedFile != null
                val locationCaptured = lat != null && lng != null
                val locationText = if (lat != null && lng != null) {
                    getString(com.example.R.string.ui_location_coordinates, lat.toString(), lng.toString())
                } else {
                    getString(com.example.R.string.ui_c0ae8d5ba4fa)
                }

                if (!isTest && eventId != null && !sendOnly) {
                    prefs.recordCaptureResult(
                        id = eventId,
                        photoPath = capturedFile?.absolutePath,
                        latitude = lat,
                        longitude = lng,
                        locationTimestamp = location?.time
                    )
                }
                if (!isTest && eventId != null) {
                    prefs.moveCapturedEventToSendPending(eventId)
                    if (sendOnly) {
                        prefs.recordLocationResult(eventId, lat, lng, location?.time)
                    }
                }

                // 3. Send Email if credentials available
                val userEmail = prefs.email
                val userPassword = prefs.password
                var emailSuccess = false
                var emailRetryable = false
                val photoStatus = when {
                    photoCaptured -> getString(com.example.R.string.ui_dd886e527edc)
                    !cameraPermissionGranted -> getString(com.example.R.string.ui_6ae598a1fca6)
                    else -> getString(com.example.R.string.ui_22a64e14f4b3)
                }
                val locationStatus = when {
                    locationCaptured -> getString(com.example.R.string.ui_c0a3068f31ba)
                    !locationPermissionGranted -> getString(com.example.R.string.ui_243471de5777)
                    else -> getString(com.example.R.string.ui_9e2833dca3b0)
                }
                var statusMsg = listOf(photoStatus, locationStatus).joinToString(getString(com.example.R.string.ui_separator))

                if (userEmail.isNotBlank() && userPassword.isNotBlank()) {
                    if (!isTest && eventId != null) prefs.updateEmailState(eventId, SecurityEventComponentState.PENDING)
                    val subject = if (isTest) {
                        getString(com.example.R.string.ui_test_subject, timeStr)
                    } else {
                        getString(com.example.R.string.ui_alert_subject, timeStr)
                    }

                    val body = """
                        ${getString(com.example.R.string.ui_greeting)}
                        
                        ${getString(if (isTest) com.example.R.string.ui_test_body else com.example.R.string.ui_intrusion_body)}
                        
                        ${getString(com.example.R.string.ui_time_label, timeStr)}
                        ${getString(com.example.R.string.ui_location_label)}
                        $locationText
                        
                        ${getString(com.example.R.string.ui_photo_attached)}
                        
                        ---
                        ${getString(com.example.R.string.ui_auto_sent)}
                    """.trimIndent()

                    val sendResult = EmailSender.sendSecurityAlert(
                        context = this@CameraForegroundService,
                        senderEmail = userEmail,
                        appPassword = userPassword,
                        recipientEmail = userEmail,
                        subject = subject,
                        bodyText = body,
                        imageFile = capturedFile,
                        eventId = eventId
                    )

                    emailSuccess = sendResult.isSuccess
                    emailRetryable = sendResult.retryable
                    if (!isTest && eventId != null) {
                        prefs.updateEmailState(
                            eventId,
                            if (emailSuccess) SecurityEventComponentState.SUCCEEDED else SecurityEventComponentState.FAILED
                        )
                    }
                    val emailStatus = if (emailSuccess) {
                        getString(com.example.R.string.ui_fd5b9e2ff279)
                    } else {
                        getString(com.example.R.string.ui_email_failed, sendResult.errorMessage ?: "")
                    }
                    statusMsg = "$statusMsg${getString(com.example.R.string.ui_separator)}$emailStatus"
                } else {
                    if (!isTest && eventId != null) prefs.updateEmailState(eventId, SecurityEventComponentState.FAILED)
                    statusMsg = "$statusMsg${getString(com.example.R.string.ui_separator)}${getString(com.example.R.string.ui_email_unconfigured)}"
                }

                if (!isTest && eventId != null) {
                    if (emailSuccess) {
                        prefs.completeSecurityEvent(eventId, SecurityEventStatus.SENT)
                    } else if (emailRetryable &&
                        userEmail.isNotBlank() && userPassword.isNotBlank() &&
                        prefs.recordSendRetry(eventId)
                    ) {
                        prefs.markSendPendingForRetry(eventId)
                        SecurityEventDistributor.enqueue(applicationContext, eventId)
                        statusMsg = "$statusMsg${getString(com.example.R.string.ui_separator)}${getString(com.example.R.string.ui_retry_scheduled)}"
                    } else {
                        prefs.completeSecurityEvent(eventId, SecurityEventStatus.FAILED_FINAL)
                        statusMsg = "$statusMsg${getString(com.example.R.string.ui_separator)}${getString(com.example.R.string.ui_final_failure)}"
                    }
                }

                // 4. Save Log
                val completedEvent = eventId?.let { prefs.getSecurityEvent(it) }
                val log = IntruderLog(
                    id = UUID.randomUUID().toString(),
                    eventId = eventId,
                    photoCaptured = photoCaptured,
                    locationCaptured = locationCaptured,
                    timestamp = timestamp,
                    photoPath = capturedFile?.absolutePath,
                    latitude = lat,
                    longitude = lng,
                    address = if (lat != null && lng != null) "$lat, $lng" else null,
                    emailSent = emailSuccess,
                    statusMessage = statusMsg,
                    photoState = completedEvent?.photoState
                        ?: if (photoCaptured) SecurityEventComponentState.SUCCEEDED else SecurityEventComponentState.FAILED,
                    locationState = completedEvent?.locationState
                        ?: if (locationCaptured) SecurityEventComponentState.SUCCEEDED else SecurityEventComponentState.FAILED,
                    emailState = completedEvent?.emailState
                        ?: if (emailSuccess) SecurityEventComponentState.SUCCEEDED else SecurityEventComponentState.FAILED
                )
                prefs.addLog(log)
                logSaved = true
                // FIX(diagnostics): the success path previously logged nothing
                // except when clearing a non-restart countdown, so screenshots
                // showed service start/destroy with no capture/email stage in
                // between. Always record the outcome incl. the email result.
                prefs.recordCountdownDiagnostic(
                    "capture",
                    if (emailSuccess) "success" else "partial",
                    "photo=$photoCaptured,location=$locationCaptured,email=$emailSuccess"
                )

                // 5. Show alert notification
                showAlertNotification(
                    isTest = isTest,
                    timeStr = timeStr,
                    emailSent = emailSuccess,
                    photoCaptured = capturedFile != null,
                    locationAvailable = location != null
                )

                if (isCountdownCapture && !autoRestartCountdown &&
                    prefs.countdownEndTime == capturedCountdownEndTime
                ) {
                    prefs.clearCountdown()
                    prefs.recordCountdownDiagnostic("capture", "completed", "countdown_cleared_after_capture")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in processIntruderCapture", e)
                prefs.recordCountdownDiagnostic(
                    "capture",
                    "failed",
                    "${e.javaClass.simpleName}:${e.message}"
                )
                if (!isTest && eventId != null) {
                    prefs.completeSecurityEvent(eventId, SecurityEventStatus.FAILED_FINAL)
                    if (!logSaved) {
                        val failedEvent = prefs.getSecurityEvent(eventId)
                        prefs.addLog(
                            IntruderLog(
                                id = UUID.randomUUID().toString(),
                                eventId = eventId,
                                photoCaptured = !failedEvent?.photoPath.isNullOrBlank(),
                                locationCaptured = failedEvent?.latitude != null && failedEvent.longitude != null,
                                timestamp = failedEvent?.timestamp ?: System.currentTimeMillis(),
                                photoPath = failedEvent?.photoPath,
                                latitude = failedEvent?.latitude,
                                longitude = failedEvent?.longitude,
                                address = if (failedEvent?.latitude != null && failedEvent.longitude != null) {
                                    "${failedEvent.latitude}, ${failedEvent.longitude}"
                                } else null,
                                emailSent = false,
                                statusMessage = getString(com.example.R.string.ui_unexpected_event, e.javaClass.simpleName),
                                photoState = failedEvent?.photoState ?: SecurityEventComponentState.FAILED,
                                locationState = failedEvent?.locationState ?: SecurityEventComponentState.FAILED,
                                emailState = failedEvent?.emailState ?: SecurityEventComponentState.FAILED
                            )
                        )
                    }
                }
                if (isCountdownCapture) {
                    if (autoRestartCountdown && !isTest && eventId != null) {
                        // FIX: after auto-restart pending=false so countdown
                        // worker would ignore the old event. Requeue old event
                        // via durable distributor instead of losing retry.
                        com.example.worker.SecurityEventDistributor.enqueue(applicationContext, eventId)
                    } else {
                        CountdownScheduler.schedulePendingRetry(applicationContext)
                    }
                }
            } finally {
                captureInProgress.set(false)
                if (wakeLock?.isHeld == true) {
                    runCatching { wakeLock.release() }
                }
                if (!isServicePersistent()) {
                    stopForeground(false)
                    stopSelf()
                }
            }
        }
    }

    private fun isServicePersistent(): Boolean {
        return false
    }

    private suspend fun captureImageSilently(): File? = withContext(Dispatchers.IO) {
        if (ActivityCompat.checkSelfPermission(this@CameraForegroundService, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted")
            return@withContext null
        }

        val cameraManager = getSystemService(CAMERA_SERVICE) as? CameraManager ?: return@withContext null

        var frontCameraId: String? = null
        try {
            val cameraIds = cameraManager.cameraIdList
            for (id in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = id
                    break
                }
            }
            if (frontCameraId == null && cameraIds.isNotEmpty()) {
                frontCameraId = cameraIds[0]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding camera", e)
            return@withContext null
        }

        if (frontCameraId == null) return@withContext null

        var outputFile: File? = null
        val captureCompleted = kotlinx.coroutines.CompletableDeferred<File?>()

        val width = 640
        val height = 480
        val reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ ir ->
            var image: android.media.Image? = null
            try {
                image = ir.acquireLatestImage()
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    val picturesDir = File(getExternalFilesDir(null), "intruder_photos")
                    if (!picturesDir.exists()) picturesDir.mkdirs()

                    val fileName = "capture_${System.currentTimeMillis()}.jpg"
                    val file = File(picturesDir, fileName)
                    FileOutputStream(file).use { fos ->
                        fos.write(bytes)
                        fos.flush()
                    }
                    outputFile = file
                    if (!captureCompleted.isCompleted) {
                        captureCompleted.complete(file)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving captured image", e)
                if (!captureCompleted.isCompleted) {
                    captureCompleted.complete(null)
                }
            } finally {
                image?.close()
                closeCamera()
            }
        }, backgroundHandler)

        try {
            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }

                        camera.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.capture(captureBuilder.build(), null, backgroundHandler)
                                    } catch (e: CameraAccessException) {
                                        Log.e(TAG, "Capture failed", e)
                                        if (!captureCompleted.isCompleted) {
                                            captureCompleted.complete(null)
                                        }
                                        closeCamera()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "Capture session configuration failed")
                                    if (!captureCompleted.isCompleted) {
                                        captureCompleted.complete(null)
                                    }
                                    closeCamera()
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting capture session", e)
                        if (!captureCompleted.isCompleted) {
                            captureCompleted.complete(null)
                        }
                        closeCamera()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    if (!captureCompleted.isCompleted) {
                        captureCompleted.complete(null)
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "CameraDevice error: $error")
                    if (!captureCompleted.isCompleted) {
                        captureCompleted.complete(null)
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            captureCompleted.complete(null)
        }

        // Wait with a 6-second timeout
        try {
            val capturedFile = kotlinx.coroutines.withTimeoutOrNull(6000L) {
                captureCompleted.await()
            }
            if (capturedFile == null) {
                Log.w(TAG, "Camera capture timed out; closing camera resources")
                if (!captureCompleted.isCompleted) {
                    captureCompleted.complete(null)
                }
                closeCamera()
            }
            capturedFile ?: outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for camera capture; closing camera resources", e)
            closeCamera()
            null
        }
    }

    private suspend fun fetchCurrentLocation(): Location? = withContext(Dispatchers.IO) {
        val hasFine = ActivityCompat.checkSelfPermission(this@CameraForegroundService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(this@CameraForegroundService, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) return@withContext null

        val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this@CameraForegroundService)
        val locationDeferred = kotlinx.coroutines.CompletableDeferred<Location?>()
        val cancellationSource = CancellationTokenSource()

        try {
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationSource.token)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        locationDeferred.complete(loc)
                    } else {
                        // fallback to last known location
                        fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
                            locationDeferred.complete(lastLoc)
                        }.addOnFailureListener {
                            locationDeferred.complete(null)
                        }
                    }
                }
                .addOnFailureListener {
                    fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
                        locationDeferred.complete(lastLoc)
                    }.addOnFailureListener {
                        locationDeferred.complete(null)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting location", e)
            locationDeferred.complete(null)
        }

        try {
            val currentLocation = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                locationDeferred.await()
            }
            currentLocation ?: run {
                // Indoor or cold-GPS fixes can exceed five seconds. Use a
                // recent cached location rather than reporting no location.
                val fallback = kotlinx.coroutines.CompletableDeferred<Location?>()
                fusedClient.lastLocation
                    .addOnSuccessListener { fallback.complete(it) }
                    .addOnFailureListener { fallback.complete(null) }
                kotlinx.coroutines.withTimeoutOrNull(2000L) { fallback.await() }
            }
        } catch (e: Exception) {
            null
        } finally {
            cancellationSource.cancel()
        }
    }

    private fun showAlertNotification(
        isTest: Boolean,
        timeStr: String,
        emailSent: Boolean,
        photoCaptured: Boolean,
        locationAvailable: Boolean
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isTest) getString(com.example.R.string.ui_12e7498cb299) else getString(com.example.R.string.ui_98c4ee03d7e4)
        val resultText = when {
            emailSent && photoCaptured && locationAvailable ->
                getString(com.example.R.string.ui_84f757b6cc1f)
            emailSent && photoCaptured ->
                getString(com.example.R.string.ui_02ef3442fe06)
            emailSent ->
                getString(com.example.R.string.ui_f2c0b25654bc)
            photoCaptured && locationAvailable ->
                getString(com.example.R.string.ui_7ea431a98540)
            photoCaptured ->
                getString(com.example.R.string.ui_cc8fa378c847)
            else ->
                getString(com.example.R.string.ui_2386801c5fa2)
        }
        val text = "$resultText ($timeStr)"

        val notification = NotificationCompat.Builder(this, SecurityApp.CHANNEL_ID_ALERTS)
            .setContentTitle(title)
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_foreground_img_1787338860864)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Notification permission not granted; skipping alert notification")
            return
        }

        try {
            NotificationManagerCompat.from(this).notify(ALERT_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error posting alert notification", e)
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? {
        return try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IntruderApp::CaptureWakeLock")?.apply {
                // FIX(email): 15s was shorter than camera(6s)+location(7s parallel)
                // + SMTP connect(15s)+read(15s). Device slept mid-send in Doze.
                acquire(60_000)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackgroundThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper ?: return)
    }

    private fun stopBackgroundThread() {
        val thread = backgroundThread ?: return
        thread.quitSafely()
        try {
            if (Thread.currentThread() !== thread) thread.join(1000L)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.w(TAG, "Unable to stop camera background thread cleanly", e)
        }
    }

    @Synchronized
    private fun closeCamera() {
        try {
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.w(TAG, "Unable to close camera resources cleanly", e)
        }
    }

    override fun onDestroy() {
        val prefs = SecurityPrefs.getInstance(applicationContext)
        prefs.recordCountdownDiagnostic(
            "service",
            "destroyed",
            "tracking=${prefs.isTrackingEnabled},countdown=${prefs.countdownEnabled},pending=${prefs.countdownCapturePending}"
        )
        // A normal user stop clears countdownEnabled first. This recovery path
        // is therefore reserved for unexpected service destruction.
        if (prefs.isTrackingEnabled && prefs.countdownEnabled) {
            prefs.recordCountdownDiagnostic("reschedule", "requested", "service_destroyed")
            CountdownScheduler.rescheduleFromPrefs(applicationContext)
        }
        serviceScope.cancel()
        foregroundStarted = false
        super.onDestroy()
        closeCamera()
        stopBackgroundThread()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "App task removed; keeping foreground protection service alive")
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
