package com.arivo.arivo_monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        AppConfig.initialize(context)
        if (AppConfig.DEVICE_KEY.isBlank()) return
        Log.i("ArivoMonitor", "BootReceiver action=$action")
        when (action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    context.createDeviceProtectedStorageContext()
                } else context
                if (!ScreenCaptureService.isCapturing) {
                    storage.getSharedPreferences("arivo_monitor_state", Context.MODE_PRIVATE)
                        .edit().putBoolean("screen_monitoring", false).apply()
                }
                start(context)

                if (action == Intent.ACTION_USER_UNLOCKED) {
                    showProjectionPermission(context)
                }
            }
        }
    }

    private fun showProjectionPermission(context: Context) {
        try {
            val intent = Intent(context, ProjectionPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            context.startActivity(intent)
        } catch (error: Exception) {
            Log.w("ArivoMonitor", "Projection permission activity could not be shown", error)
        }
    }

    private fun start(context: Context) {
        val service = Intent(context, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}
