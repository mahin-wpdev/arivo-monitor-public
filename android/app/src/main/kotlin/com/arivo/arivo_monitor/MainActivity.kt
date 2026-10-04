package com.arivo.arivo_monitor

import android.Manifest
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
        handleActivationIntent(intent)
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
                if (call.method == "startMonitoring") {
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
                } else result.notImplemented()
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
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.POST_NOTIFICATIONS
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
