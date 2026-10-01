package com.arivo.arivo_monitor

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

class ProjectionPermissionActivity : Activity() {
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppConfig.initialize(applicationContext)
        StartupRecordingConsent.opened(this)
        if (AppConfig.DEVICE_KEY.isBlank()) {
            finishAndRemoveTask()
            return
        }
        launched = savedInstanceState?.getBoolean(KEY_LAUNCHED, false) ?: false
        if (!launched) {
            if (!ScreenCaptureConsent.begin()) {
                finishAndRemoveTask()
                return
            }
            launched = true
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_LAUNCHED, launched)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android API, retained for MediaProjection compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE &&
            (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED ||
             androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            val monitor = Intent(this, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(monitor)
            else startService(monitor)
        }

        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, ScreenCaptureService::class.java)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service)
            } else {
                startService(service)
            }
        }

        if (requestCode == REQUEST_CODE && (resultCode != RESULT_OK || data == null)) ScreenCaptureConsent.complete()

        finishAndRemoveTask()
    }

    companion object {
        private const val REQUEST_CODE = 4102
        private const val KEY_LAUNCHED = "projection_permission_launched"
    }
}
