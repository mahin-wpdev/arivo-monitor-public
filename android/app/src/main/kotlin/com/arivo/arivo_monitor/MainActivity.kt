package com.arivo.arivo_monitor

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val channelName = "com.arivo.monitor/control"
    private val permissionRequest = 1001
    private val projectionRequest = 2001
    private val batteryOptimizationRequest = 2002
    private val setupWizardRequest = 2003
    private val activationIo = Executors.newSingleThreadExecutor()
    private var activationPending = false
    private var permissionFlowPending = false
    private var projectionRequested = false
    private var setupWizardLaunched = false
    private val foregroundHandler = Handler(Looper.getMainLooper())
    private val updatePoll = object : Runnable {
        override fun run() {
            AppUpdateManager.checkAndPrompt(this@MainActivity)
            foregroundHandler.postDelayed(this, 15000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionRequested = savedInstanceState?.getBoolean("projection_requested", false) ?: false
        recordPreviousUserStop()
        recordPermissionAndBatteryChanges()
        handleActivationIntent(intent)
    }

    private fun recordPermissionAndBatteryChanges() {
        val prefs = getSharedPreferences("arivo_diagnostics", Context.MODE_PRIVATE)
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val notificationsEnabled = areAppNotificationsEnabled()
        val state = JSONObject()
            .put("fine_location", checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            .put("coarse_location", checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            .put("background_location", Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
            .put("notifications", notificationsEnabled)
            .put("battery_optimization_exempt", power.isIgnoringBatteryOptimizations(packageName))
        val previousRaw = prefs.getString("last_permission_battery_state", null)
        if (!previousRaw.isNullOrBlank()) {
            val previous = try { JSONObject(previousRaw) } catch (_: Exception) { JSONObject() }
            val removed = JSONObject()
            for (key in listOf("fine_location", "coarse_location", "background_location", "notifications")) {
                val before = previous.optBoolean(key, state.optBoolean(key))
                val current = state.optBoolean(key)
                if (before && !current) removed.put(key, true)
            }
            if (removed.length() > 0) {
                MonitorDiagnostics.record(this, "permissions_removed", JSONObject()
                    .put("permissions_removed", removed)
                    .put("notification_permission", notificationsEnabled)
                    .put("battery_optimization_exempt", power.isIgnoringBatteryOptimizations(packageName)))
            }
            if (previous.optBoolean("battery_optimization_exempt", state.optBoolean("battery_optimization_exempt")) !=
                state.optBoolean("battery_optimization_exempt")) {
                MonitorDiagnostics.record(this, "battery_optimization_setting_changed", JSONObject()
                    .put("battery_optimization_exempt", power.isIgnoringBatteryOptimizations(packageName)))
            }
        }
        prefs.edit().putString("last_permission_battery_state", state.toString()).apply()
    }

    private fun recordPreviousUserStop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = getSharedPreferences("arivo_diagnostics", Context.MODE_PRIVATE)
        val lastRecorded = prefs.getLong("last_process_exit_timestamp", 0L)
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exit = try {
            manager.getHistoricalProcessExitReasons(packageName, 0, 10)
                .firstOrNull { it.timestamp > lastRecorded }
        } catch (_: Exception) {
            null
        } ?: return

        val notificationsAllowed = areAppNotificationsEnabled()
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val (reason, certainty, label) = when (exit.reason) {
            ApplicationExitInfo.REASON_USER_REQUESTED -> Triple(
                "app_stopped_by_user", "os_reported", "Android reported a user-requested stop"
            )
            ApplicationExitInfo.REASON_LOW_MEMORY -> Triple(
                "app_stopped_low_memory", "os_reported", "Android reported low memory"
            )
            ApplicationExitInfo.REASON_CRASH -> Triple(
                "app_crashed", "os_reported", "Android reported an app crash"
            )
            ApplicationExitInfo.REASON_CRASH_NATIVE -> Triple(
                "app_native_crash", "os_reported", "Android reported a native crash"
            )
            ApplicationExitInfo.REASON_ANR -> Triple(
                "app_anr", "os_reported", "Android reported that the app stopped responding"
            )
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> Triple(
                "app_excessive_resource_usage", "os_reported", "Android reported excessive resource use"
            )
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> Triple(
                "app_permission_change_exit", "os_reported", "Android reported a permission change"
            )
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> Triple(
                "app_package_state_change_exit", "os_reported", "Android reported a package state change"
            )
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> Triple(
                "app_package_updated_exit", "os_reported", "Android reported that the app was updated"
            )
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> Triple(
                "app_dependency_died", "os_reported", "Android reported that a dependency stopped"
            )
            else -> Triple(
                "app_process_exit_other", "os_reported_unspecified", "Android reported process exit: ${exit.reason}"
            )
        }
        MonitorDiagnostics.record(
            this,
            reason,
            JSONObject()
                .put("exit_timestamp", exit.timestamp)
                .put("exit_reason_code", exit.reason)
                .put("exit_reason_label", label)
                .put("exit_description", exit.description ?: "")
                .put("diagnostic_certainty", certainty)
                .put("notification_permission", notificationsAllowed)
                .put("battery_optimization_exempt", power.isIgnoringBatteryOptimizations(packageName))
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("app_build", BuildConfig.VERSION_CODE)
        )
        if (exit.reason == ApplicationExitInfo.REASON_USER_REQUESTED) {
            MonitorDiagnostics.serviceStopped(this)
        }
        prefs.edit().putLong("last_process_exit_timestamp", exit.timestamp).apply()
    }

    private fun areAppNotificationsEnabled(): Boolean {
        val appNotificationsEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .areNotificationsEnabled()
        val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return appNotificationsEnabled && runtimePermissionGranted
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleActivationIntent(intent)
    }

    private fun handleActivationIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "arivo" || uri.host != "activate") return
        val code = uri.getQueryParameter("code") ?: return
        activate(code)
        // Do not retain the one-time code in the activity's current intent.
        intent.data = null
    }

    override fun onResume() {
        super.onResume()
        AppConfig.initialize(applicationContext)
        val needsActivation = AppConfig.DEVICE_KEY.isBlank()
        if (needsActivation) {
            activate()
        }

        if (showSetupWizardIfNeeded()) return

        setupWizardLaunched = false
        if (needsActivation) {
            // Permissions are already verified by the one-time setup wizard.
            // Enrollment and heartbeat can still recover after a network failure.
            ensurePermissionsAndStart()
        }
        foregroundHandler.removeCallbacks(updatePoll)
        foregroundHandler.postDelayed(updatePoll, 700)
    }

    private fun showSetupWizardIfNeeded(): Boolean {
        if (!SetupWizardActivity.shouldShow(this)) return false
        if (!setupWizardLaunched) {
            setupWizardLaunched = true
            startActivityForResult(
                Intent(this, SetupWizardActivity::class.java),
                setupWizardRequest
            )
        }
        return true
    }

    override fun onPause() {
        foregroundHandler.removeCallbacks(updatePoll)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("projection_requested", projectionRequested)
        super.onSaveInstanceState(outState)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startMonitoring" -> {
                        if (showSetupWizardIfNeeded()) {
                            result.success(true)
                            return@setMethodCallHandler
                        }
                        if (AppConfig.DEVICE_KEY.isBlank()) {
                            activate()
                            result.success(true)
                            return@setMethodCallHandler
                        }
                        ensurePermissionsAndStart()
                        result.success(true)
                    }
                    "getUpdateStatus" -> result.success(AppUpdateManager.status(this))
                    "checkForUpdates" -> {
                        AppUpdateManager.checkNow(this)
                        result.success(true)
                    }
                    "installUpdate" -> {
                        AppUpdateManager.installPending(this)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun activate(code: String? = null) {
        if (code != null && !code.replace(Regex("[\\s-]"), "").matches(Regex("[a-fA-F0-9]{20}"))) {
            return
        }
        if (activationPending || (code == null && AppConfig.DEVICE_KEY.isNotBlank())) return
        activationPending = true
        activationIo.execute {
            var connection: HttpURLConnection? = null
            try {
                require(AppConfig.SERVER_BASE_URL.startsWith("https://"))
                val deviceId = AppConfig.deviceId(applicationContext)
                connection = URL(AppConfig.SERVER_BASE_URL + if (code == null) "/device/auto-enroll" else "/device/enroll")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val body = if (code == null) JSONObject() else JSONObject().put("code", code).put("device_id", deviceId)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode != 200) throw IllegalStateException("Activation rejected")
                val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                if (code == null) AppConfig.saveToken(applicationContext,
                    response.getString("token"), response.getString("device_id"))
                else AppConfig.saveToken(applicationContext, response.getString("token"))
                getSharedPreferences("arivo_app_update", MODE_PRIVATE).edit().putLong("last_check", 0L).apply()
                runOnUiThread {
                    if (!isFinishing) {
                        if (!showSetupWizardIfNeeded()) {
                            ensurePermissionsAndStart()
                        }
                        AppUpdateManager.checkAndPrompt(this)
                    }
                }
            } catch (_: Exception) {
                // The dashboard shows connection through the next heartbeat.
                // A failed or expired link can be replaced there without logging credentials.
            } finally {
                connection?.disconnect()
                runOnUiThread { activationPending = false }
            }
        }
    }

    private fun ensurePermissionsAndStart() {
        if (permissionFlowPending) return
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (needed.isNotEmpty()) {
            permissionFlowPending = true
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), permissionRequest)
        } else {
            startMonitorService()
            requestBatteryOptimizationOrCapture()
        }
    }

    private fun requestBatteryOptimizationOrCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            requestScreenCapture()
            return
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val prefs = getSharedPreferences("arivo_local_setup", MODE_PRIVATE)
        if (powerManager.isIgnoringBatteryOptimizations(packageName) ||
            prefs.getBoolean("battery_optimization_prompted", false)) {
            requestScreenCapture()
            return
        }
        try {
            startActivityForResult(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                },
                batteryOptimizationRequest
            )
            prefs.edit().putBoolean("battery_optimization_prompted", true).apply()
        } catch (_: Exception) {
            requestScreenCapture()
        }
    }

    private fun startMonitorService() {
        val intent = Intent(this, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun requestScreenCapture() {
        if (projectionRequested || !ScreenCaptureConsent.begin()) return
        projectionRequested = true
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
        } catch (error: Exception) {
            ScreenCaptureConsent.complete()
            throw error
        }
    }

    private fun openNotificationSettingsOnce() {
        val prefs = getSharedPreferences("arivo_local_setup", MODE_PRIVATE)
        if (prefs.getBoolean("notification_settings_shown", false)) return
        prefs.edit().putBoolean("notification_settings_shown", true).apply()

        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) {
            permissionFlowPending = false
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return
            startMonitorService()
            requestBatteryOptimizationOrCapture()
        }
    }

    @Deprecated("Deprecated in Android API, retained for MediaProjection compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == setupWizardRequest) {
            setupWizardLaunched = false
            if (SetupWizardActivity.isSetupComplete(this)) {
                ensurePermissionsAndStart()
            }
            return
        }
        if (requestCode == batteryOptimizationRequest) {
            requestScreenCapture()
            return
        }
        if (requestCode == projectionRequest && resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
        if (requestCode == projectionRequest) {
            if (resultCode != Activity.RESULT_OK || data == null) ScreenCaptureConsent.complete()
            Handler(Looper.getMainLooper()).postDelayed({
                openNotificationSettingsOnce()
            }, 900)
        }
    }
}
