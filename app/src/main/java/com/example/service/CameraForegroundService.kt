package com.example.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.example.MainActivity
import com.example.R
import com.example.SecurityApp
import com.example.data.IntruderLog
import com.example.data.SecurityPrefs
import com.example.receiver.CountdownScheduler
import com.example.util.EmailSender
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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

    override fun onCreate() {
        super.onCreate()
        startBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_CAPTURE_AND_SEND
        Log.d(TAG, "onStartCommand action: $action")

        val notification = buildForegroundNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error", e)
        }

        when (action) {
            ACTION_CAPTURE_AND_SEND, ACTION_COUNTDOWN_EXPIRED, ACTION_TEST_CAPTURE -> {
                val isTest = action == ACTION_TEST_CAPTURE
                processIntruderCapture(isTest, action == ACTION_COUNTDOWN_EXPIRED)
            }
            ACTION_STOP_MONITORING -> {
                stopForeground(true)
                stopSelf()
            }
            ACTION_START_MONITORING -> {
                // Keep running as background guard
            }
        }

        return START_NOT_STICKY
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
            .setContentTitle("حماية الهاتف نشطة")
            .setContentText("جاري مراقبة محاولات فتح القفل غير المصرح بها")
            .setSmallIcon(R.drawable.ic_launcher_foreground_img_1787338860864)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun processIntruderCapture(isTest: Boolean, isCountdownCapture: Boolean = false) {
        if (!captureInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Capture already in progress; ignoring duplicate request")
            if (isCountdownCapture) {
                CountdownScheduler.schedulePendingRetry(applicationContext)
            }
            return
        }

        val wakeLock = acquireWakeLock()

        serviceScope.launch {
            try {
                val prefs = SecurityPrefs.getInstance(applicationContext)
                val timestamp = System.currentTimeMillis()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val timeStr = dateFormat.format(Date(timestamp))

                // 1. Capture Image
                val capturedFile = captureImageSilently()
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

                // 2. Fetch Location
                val location = fetchCurrentLocation()
                val lat = location?.latitude
                val lng = location?.longitude
                val locationText = if (lat != null && lng != null) {
                    "خط العرض (Latitude): $lat\nخط الطول (Longitude): $lng\nرابط خرائط جوجل:\nhttps://www.google.com/maps?q=$lat,$lng"
                } else {
                    "الموقع غير متاح (تعذر تحديد إحداثيات GPS أو تم تعطيل الصلاحية)"
                }

                // 3. Send Email if credentials available
                val userEmail = prefs.email
                val userPassword = prefs.password
                var emailSuccess = false
                var statusMsg = when {
                    !cameraPermissionGranted -> "تعذر التقاط الصورة: إذن الكاميرا غير ممنوح"
                    capturedFile == null -> "تعذر التقاط الصورة من الكاميرا"
                    !locationPermissionGranted -> "تم التقاط الصورة، لكن إذن الموقع غير ممنوح"
                    else -> ""
                }

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
                        imageFile = capturedFile
                    )

                    emailSuccess = sendResult.isSuccess
                    val emailStatus = if (emailSuccess) {
                        "تم إرسال بريد التنبيه بنجاح مع الصورة والموقع"
                    } else {
                        "فشل الإرسال: ${sendResult.errorMessage}"
                    }
                    statusMsg = listOf(statusMsg, emailStatus)
                        .filter { it.isNotBlank() }
                        .joinToString("؛ ")
                } else {
                    statusMsg = if (statusMsg.isBlank()) {
                        "تم حفظ الصورة محلياً (البريد الإلكتروني غير مهيأ)"
                    } else {
                        "$statusMsg؛ البريد الإلكتروني غير مهيأ، لذلك لم يتم الإرسال"
                    }
                }

                // 4. Save Log
                val log = IntruderLog(
                    id = UUID.randomUUID().toString(),
                    timestamp = timestamp,
                    photoPath = capturedFile?.absolutePath,
                    latitude = lat,
                    longitude = lng,
                    address = if (lat != null && lng != null) "$lat, $lng" else null,
                    emailSent = emailSuccess,
                    statusMessage = statusMsg
                )
                prefs.addLog(log)

                // 5. Show alert notification
                showAlertNotification(isTest, timeStr, emailSuccess)

                if (isCountdownCapture) {
                    prefs.clearCountdown()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in processIntruderCapture", e)
                if (isCountdownCapture) {
                    CountdownScheduler.schedulePendingRetry(applicationContext)
                }
            } finally {
                captureInProgress.set(false)
                wakeLock?.release()
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
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = id
                    break
                }
            }
            if (frontCameraId == null && cameraManager.cameraIdList.isNotEmpty()) {
                frontCameraId = cameraManager.cameraIdList[0]
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
            try {
                val image = ir.acquireLatestImage()
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    val picturesDir = File(getExternalFilesDir(null), "intruder_photos")
                    if (!picturesDir.exists()) picturesDir.mkdirs()

                    val fileName = "capture_${System.currentTimeMillis()}.jpg"
                    val file = File(picturesDir, fileName)
                    FileOutputStream(file).use { fos ->
                        fos.write(bytes)
                        fos.flush()
                    }
                    outputFile = file
                    captureCompleted.complete(file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving captured image", e)
                captureCompleted.complete(null)
            } finally {
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
                                        captureCompleted.complete(null)
                                        closeCamera()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "Capture session configuration failed")
                                    captureCompleted.complete(null)
                                    closeCamera()
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting capture session", e)
                        captureCompleted.complete(null)
                        closeCamera()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    captureCompleted.complete(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "CameraDevice error: $error")
                    captureCompleted.complete(null)
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            captureCompleted.complete(null)
        }

        // Wait with a 6-second timeout
        try {
            kotlinx.coroutines.withTimeoutOrNull(6000L) {
                captureCompleted.await()
            } ?: outputFile
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchCurrentLocation(): Location? = withContext(Dispatchers.IO) {
        val hasFine = ActivityCompat.checkSelfPermission(this@CameraForegroundService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(this@CameraForegroundService, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) return@withContext null

        val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this@CameraForegroundService)
        val locationDeferred = kotlinx.coroutines.CompletableDeferred<Location?>()

        try {
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
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
            kotlinx.coroutines.withTimeoutOrNull(5000L) {
                locationDeferred.await()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun showAlertNotification(isTest: Boolean, timeStr: String, emailSent: Boolean) {
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
        val text = if (emailSent) {
            "تم التقاط الصورة وإرسال التنبيه إلى بريدك ($timeStr)"
        } else {
            "تم التقاط الصورة وتسجيل المحاولة في $timeStr"
        }

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
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun closeCamera() {
        try {
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        closeCamera()
        stopBackgroundThread()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
