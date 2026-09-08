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
import com.example.receiver.CountdownScheduler
import com.example.util.EmailSender
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

    override fun onCreate() {
        super.onCreate()
        val prefs = SecurityPrefs.getInstance(applicationContext)
        prefs.recoverStaleSecurityEvents()
        // Rebuild alarms when Android/OEM process cleanup removed them while
        // the persisted protection countdown is still active.
        CountdownScheduler.rescheduleFromPrefs(applicationContext)
        startBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_CAPTURE_AND_SEND
        Log.d(TAG, "onStartCommand action: $action")

        if (action == ACTION_STOP_MONITORING) {
            NotificationManagerCompat.from(this).cancel(ALERT_NOTIFICATION_ID)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (!foregroundStarted) {
            if (!promoteToForeground(buildForegroundNotification())) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            foregroundStarted = true
        }

        when (action) {
            ACTION_CAPTURE_AND_SEND, ACTION_COUNTDOWN_EXPIRED, ACTION_TEST_CAPTURE -> {
                val isTest = action == ACTION_TEST_CAPTURE
                val eventId = intent?.getStringExtra(EXTRA_SECURITY_EVENT_ID)
                processIntruderCapture(isTest, action == ACTION_COUNTDOWN_EXPIRED, eventId)
            }
            ACTION_START_MONITORING -> Unit
        }

        // Ask Android to recreate the service after a system-initiated kill
        // while protection is enabled. Explicit user stop above remains
        // START_NOT_STICKY and therefore is not resurrected.
        return if (SecurityPrefs.getInstance(applicationContext).isTrackingEnabled) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    private fun promoteToForeground(notification: Notification): Boolean {
        val hasCameraPermission = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) {
            Log.e(TAG, "Cannot start camera foreground service without CAMERA permission")
            return false
        }

        val hasLocationPermission = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
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
        val existingEvent = eventId?.let { prefs.getSecurityEvent(it) }
        val sendOnly = !isTest && existingEvent?.status in setOf(
            SecurityEventStatus.SEND_PENDING,
            SecurityEventStatus.FAILED_RETRYABLE
        ) && !existingEvent?.photoPath.isNullOrBlank()
        if (!isTest && eventId != null && !sendOnly && !prefs.claimSecurityEvent(eventId)) {
            Log.w(TAG, "Ignoring duplicate or already-processed security event: $eventId")
            return
        }
        if (!captureInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Capture already in progress; ignoring duplicate request")
            if (!isTest && eventId != null) prefs.completeSecurityEvent(eventId, SecurityEventStatus.PENDING)
            if (isCountdownCapture) {
                CountdownScheduler.schedulePendingRetry(applicationContext)
            }
            if (!isTest && eventId != null) {
                serviceScope.launch {
                    while (captureInProgress.get()) kotlinx.coroutines.delay(250L)
                    processIntruderCapture(isTest = false, isCountdownCapture = false, eventId = eventId)
                }
            }
            return
        }

        // Schedule the next countdown before touching camera, location, or email.
        // A failure in any of those operations must not stop an auto-restarting timer.
        val autoRestartCountdown = isCountdownCapture &&
            prefs.countdownAutoRestart &&
            prefs.isTrackingEnabled
        if (autoRestartCountdown) {
            CountdownScheduler.start(applicationContext, prefs.countdownDurationMillis)
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
                    "خط العرض (Latitude): $lat\nخط الطول (Longitude): $lng\nرابط خرائط جوجل:\nhttps://www.google.com/maps?q=$lat,$lng"
                } else {
                    "الموقع غير متاح (تعذر تحديد إحداثيات GPS أو تم تعطيل الصلاحية)"
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

                // 3. Send Email if credentials available
                val userEmail = prefs.email
                val userPassword = prefs.password
                var emailSuccess = false
                var emailRetryable = false
                val photoStatus = when {
                    photoCaptured -> "الصورة: تم الالتقاط"
                    !cameraPermissionGranted -> "الصورة: فشل الالتقاط، إذن الكاميرا غير ممنوح"
                    else -> "الصورة: فشل الالتقاط"
                }
                val locationStatus = when {
                    locationCaptured -> "الموقع: تم التحديد"
                    !locationPermissionGranted -> "الموقع: غير متاح، إذن الموقع غير ممنوح"
                    else -> "الموقع: تعذر التحديد"
                }
                var statusMsg = listOf(photoStatus, locationStatus).joinToString("؛ ")

                if (userEmail.isNotBlank() && userPassword.isNotBlank()) {
                    val subject = if (isTest) {
                        "🔔 اختبار كاشف المتسللين: نجاح التجربة ($timeStr)"
                    } else {
                        "🚨 تحذير أمني عاجل: محاولة فتح هاتف غير مصرح بها! ($timeStr)"
                    }

                    val body = """
                        تحية طيبة،
                        
                        ${if (isTest) "هذه رسالة اختبارية من تطبيق حماية الهاتف لتأكيد صحة إعدادات البريد الإلكتروني والكاميرا والموقع." else "تم رصد محاولة إدخال كلمة مرور أو نمط خاطئ على هاتفك المحمول."}
                        
                        📅 التوقيت: $timeStr
                        📍 الموقع الجغرافي:
                        $locationText
                        
                        📷 صورة الكاميرا الأمامية: مرفقة مع هذه الرسالة.
                        
                        ---
                        تم الإرسال تلقائياً بواسطة تطبيق حماية الهاتف وكاشف المتسللين.
                    """.trimIndent()

                    val sendResult = EmailSender.sendSecurityAlert(
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
                    val emailStatus = if (emailSuccess) {
                        "البريد: تم الإرسال"
                    } else {
                        "البريد: فشل الإرسال: ${sendResult.errorMessage}"
                    }
                    statusMsg = "$statusMsg؛ $emailStatus"
                } else {
                    statusMsg = "$statusMsg؛ البريد: غير مهيأ، لم يتم الإرسال"
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
                        statusMsg = "$statusMsg؛ فشل مؤقت: تمت جدولة إعادة إرسال البريد دون إعادة التقاط الصورة"
                    } else {
                        prefs.completeSecurityEvent(eventId, SecurityEventStatus.FAILED_FINAL)
                        statusMsg = "$statusMsg؛ فشل نهائي: لن تتم إعادة المحاولة تلقائياً"
                    }
                }

                // 4. Save Log
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
                    statusMessage = statusMsg
                )
                prefs.addLog(log)
                logSaved = true

                // 5. Show alert notification
                showAlertNotification(
                    isTest = isTest,
                    timeStr = timeStr,
                    emailSent = emailSuccess,
                    photoCaptured = capturedFile != null,
                    locationAvailable = location != null
                )

                if (isCountdownCapture && !autoRestartCountdown) {
                    prefs.clearCountdown()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in processIntruderCapture", e)
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
                                statusMessage = "FAILED_FINAL: فشل غير متوقع أثناء معالجة الحدث: ${e.javaClass.simpleName}"
                            )
                        )
                    }
                }
                if (isCountdownCapture) {
                    CountdownScheduler.schedulePendingRetry(applicationContext)
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
        val prefs = SecurityPrefs.getInstance(applicationContext)
        return prefs.isTrackingEnabled
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

        val title = if (isTest) "اختبار كاشف المتسللين" else "🚨 تنبيه أمان: محاولة فتح خاطئة!"
        val resultText = when {
            emailSent && photoCaptured && locationAvailable ->
                "تم التقاط الصورة وتحديد الموقع وإرسال التنبيه إلى بريدك"
            emailSent && photoCaptured ->
                "تم التقاط الصورة وإرسال التنبيه؛ الموقع غير متاح"
            emailSent ->
                "تم إرسال التنبيه؛ تعذر التقاط الصورة أو تحديد الموقع"
            photoCaptured && locationAvailable ->
                "تم التقاط الصورة وتحديد الموقع، لكن تعذر إرسال البريد"
            photoCaptured ->
                "تم التقاط الصورة، لكن الموقع أو إرسال البريد غير متاح"
            else ->
                "تم تسجيل المحاولة، لكن التقاط الصورة لم ينجح"
        }
        val text = "$resultText ($timeStr)"

        val notification = NotificationCompat.Builder(this, SecurityApp.CHANNEL_ID_ALERTS)
            .setContentTitle(title)
            .setContentText(text)
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
                acquire(15000)
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
        // A normal user stop clears countdownEnabled first. Therefore this
        // only repairs scheduling after an unexpected service destruction.
        val prefs = SecurityPrefs.getInstance(applicationContext)
        if (prefs.isTrackingEnabled && prefs.countdownEnabled) {
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
