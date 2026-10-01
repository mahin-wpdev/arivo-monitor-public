package com.arivo.arivo_monitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object StartupRecordingConsent {
    private const val CHANNEL = "arivo_recording_consent"
    private const val NOTIFICATION = 4102

    fun request(context: Context) {
        if (AppConfig.DEVICE_KEY.isBlank() || ScreenCaptureService.isCapturing || ScreenCaptureConsent.isPending()) return
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val prefs = context.getSharedPreferences("arivo_startup_consent", Context.MODE_PRIVATE)
        if (boot >= 0 && prefs.getInt("requested_boot", -2) == boot) return
        prefs.edit().putInt("requested_boot", boot).apply()

        val intent = Intent(context, ProjectionPermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                "Screen recording permission", NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val pending = PendingIntent.getActivity(context, NOTIFICATION, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(NOTIFICATION, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Arivo screen recording")
                .setContentText("Tap to choose whether to start screen sharing after restart.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build())
        }
        // Android may block background activity starts without throwing. The
        // visible notification remains available in that case; no bypass is used.
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Log.i("ArivoMonitor", "Startup consent requires notification tap")
        }
    }

    fun opened(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }
}
