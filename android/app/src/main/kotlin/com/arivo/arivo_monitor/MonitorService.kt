package com.arivo.arivo_monitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MonitorService : Service(), LocationListener {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var locationManager: LocationManager
    private lateinit var deviceId: String
    private var lastAcceptedLocation: Location? = null
    private var lastLocationAcceptedAt = 0L
    private val queueLock = Any()
    override fun onCreate() {
        super.onCreate()
        deviceId = AppConfig.deviceId(applicationContext)
        createChannel()
        startForeground(
            1001,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
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

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        MonitorDiagnostics.serviceStarted(this, diagnosticContext())
        startLocationUpdates()
        scheduler.scheduleAtFixedRate(
            { sendHeartbeat() },
            0,
            AppConfig.HEARTBEAT_SECONDS,
            TimeUnit.SECONDS
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        MonitorDiagnostics.record(
            this,
            "task_removed_from_recents",
            diagnosticContext().put("service_restart_attempted", false)
        )
        super.onTaskRemoved(rootIntent)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Arivo Status",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Arivo background status"
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
                }
            )
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider,
                        AppConfig.LOCATION_MIN_TIME_MS,
                        AppConfig.LOCATION_MIN_DISTANCE_M,
                        this
                    )
                }
            } catch (_: Exception) {}
        }
    }

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        val previous = lastAcceptedLocation
        val distance = previous?.distanceTo(location) ?: Float.MAX_VALUE
        val moving = (location.hasSpeed() && location.speed >= 1.0f) || distance >= 30f
        val minInterval = if (moving) AppConfig.LOCATION_MOVING_UPLOAD_MS
            else AppConfig.LOCATION_STATIONARY_UPLOAD_MS

        if (previous != null && now - lastLocationAcceptedAt < minInterval) return
        if (!moving && previous != null && distance < 15f &&
            now - lastLocationAcceptedAt < AppConfig.LOCATION_STATIONARY_UPLOAD_MS
        ) return

        lastAcceptedLocation = Location(location)
        lastLocationAcceptedAt = now

        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy", location.accuracy.toDouble())
            put("altitude", if (location.hasAltitude()) location.altitude else JSONObject.NULL)
            put("speed", if (location.hasSpeed()) location.speed.toDouble() else JSONObject.NULL)
            put("provider", location.provider ?: "")
            put("device_time", now)
        }

        io.execute {
            withShortWakeLock("LocationSync") {
                if (isOnline()) {
                    if (postJsonSync("/device/location", body)) flushLocationQueue()
                    else enqueueLocation(body)
                } else {
                    enqueueLocation(body)
                }
            }
        }
    }
    private fun sendHeartbeat() {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val battery = if (level >= 0) ((level * 100f) / scale).toInt() else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val prefs = statePrefs()
        val updatePrefs = getSharedPreferences("arivo_app_update", MODE_PRIVATE)

        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("device_name", Build.DEVICE ?: "")
            put("model", Build.MODEL ?: "")
            put("manufacturer", Build.MANUFACTURER ?: "")
            put("android_version", Build.VERSION.RELEASE ?: "")
            put("sdk", Build.VERSION.SDK_INT)
            put("battery", battery)
            put("charging", charging)
            put("network", currentNetwork())
            put("gps_enabled", gpsEnabled())
            put("screen_monitoring", prefs.getBoolean("screen_monitoring", false))
            put("app_version", BuildConfig.VERSION_NAME)
            put("app_build", BuildConfig.VERSION_CODE)
            put("update_status", updatePrefs.getString("status", "unknown"))
            put("update_progress", updatePrefs.getInt("progress", 0))
            put("latest_version", updatePrefs.getString("latest_version", ""))
            put("latest_build", updatePrefs.getInt("latest_build", 0))
        }

        io.execute {
            withShortWakeLock("HeartbeatSync") {
                val failure = if (isOnline()) postHeartbeatSync(body) else "network_unavailable"
                if (failure == null) {
                    val events = MonitorDiagnostics.pending(this@MonitorService)
                    if (events != null) {
                        val report = JSONObject().put("device_id", deviceId).put("events", events)
                        if (postJsonSync("/device/diagnostics", report)) {
                            MonitorDiagnostics.clear(this@MonitorService)
                        }
                    }
                    flushLocationQueue()
                } else {
                    MonitorDiagnostics.record(this@MonitorService, failure, diagnosticContext())
                }
                AppUpdateManager.backgroundCheck(applicationContext)
            }
        }
    }

    private fun diagnosticContext(): JSONObject {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        return JSONObject()
            .put("network", currentNetwork())
            .put("battery_percent", if (level >= 0) ((level * 100f) / scale).toInt() else JSONObject.NULL)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL)
            .put("battery_saver", power.isPowerSaveMode)
            .put("interactive", power.isInteractive)
            .put("gps_enabled", gpsEnabled())
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("app_build", BuildConfig.VERSION_CODE)
            .put("android_sdk", Build.VERSION.SDK_INT)
    }

    private fun postHeartbeatSync(body: JSONObject): String? {
        return try {
            val connection = URL(AppConfig.SERVER_BASE_URL + "/device/heartbeat")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Arivo-Key", AppConfig.DEVICE_KEY)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) connection.inputStream.use { it.readBytes() }
            else connection.errorStream?.use { it.readBytes() }
            connection.disconnect()
            if (code in 200..299) null else "server_http_$code"
        } catch (_: java.net.SocketTimeoutException) {
            "request_timeout"
        } catch (_: java.net.UnknownHostException) {
            "dns_failure"
        } catch (_: java.net.ConnectException) {
            "connection_refused"
        } catch (error: Exception) {
            "request_error_${error.javaClass.simpleName.take(40)}"
        }
    }

    private fun enqueueLocation(body: JSONObject) {
        synchronized(queueLock) {
            val file = File(filesDir, LOCATION_QUEUE_FILE)
            val rows = if (file.exists()) file.readLines().filter { it.isNotBlank() }.toMutableList()
            else mutableListOf()
            rows.add(body.toString())
            while (rows.size > MAX_LOCATION_QUEUE) rows.removeAt(0)
            file.writeText(rows.joinToString("\n", postfix = if (rows.isEmpty()) "" else "\n"))
        }
    }

    private fun flushLocationQueue() {
        if (!isOnline()) return
        synchronized(queueLock) {
            val file = File(filesDir, LOCATION_QUEUE_FILE)
            if (!file.exists()) return
            val rows = file.readLines().filter { it.isNotBlank() }.toMutableList()
            if (rows.isEmpty()) {
                file.delete()
                return
            }

            var sent = 0
            while (rows.isNotEmpty() && sent < MAX_LOCATION_FLUSH_PER_RUN) {
                val body = try { JSONObject(rows.first()) } catch (_: Exception) {
                    rows.removeAt(0)
                    continue
                }
                if (!postJsonSync("/device/location", body)) break
                rows.removeAt(0)
                sent++
            }

            if (rows.isEmpty()) file.delete()
            else file.writeText(rows.joinToString("\n", postfix = "\n"))
        }
    }
    private fun currentNetwork(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "Offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "Offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun gpsEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    private inline fun <T> withShortWakeLock(tag: String, block: () -> T): T {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Arivo:$tag")
        lock.setReferenceCounted(false)
        return try {
            lock.acquire(SHORT_WAKE_LOCK_MS)
            block()
        } finally {
            try { if (lock.isHeld) lock.release() } catch (_: Exception) {}
        }
    }

    private fun postJsonSync(path: String, body: JSONObject): Boolean {
        return try {
            val connection = URL(AppConfig.SERVER_BASE_URL + path)
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Arivo-Key", AppConfig.DEVICE_KEY)
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
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

    private fun statePrefs() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext()
                .getSharedPreferences("arivo_monitor_state", MODE_PRIVATE)
        } else {
            getSharedPreferences("arivo_monitor_state", MODE_PRIVATE)
        }

    override fun onDestroy() {
        MonitorDiagnostics.serviceStopped(this)
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        scheduler.shutdownNow()
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "arivo_monitor_status_v2"
        private const val LOCATION_QUEUE_FILE = "arivo_location_queue.jsonl"
        private const val MAX_LOCATION_QUEUE = 300
        private const val MAX_LOCATION_FLUSH_PER_RUN = 30
        private const val SHORT_WAKE_LOCK_MS = 15_000L
    }
}
