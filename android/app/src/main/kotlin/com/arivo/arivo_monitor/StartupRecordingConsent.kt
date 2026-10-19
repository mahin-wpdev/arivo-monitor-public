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

/**
 * Re-requests the MediaProjection consent that Android drops whenever the app
 * process dies (reboot, force-stop, OEM kill). Android never lets an app grant
 * this silently, but a full-screen-intent notification is the sanctioned way to
 * surface the consent screen over whatever the user is currently doing, so the
 * prompt appears on its own instead of the user having to find and open the app.
 */
object StartupRecordingConsent {
    private const val CHANNEL = "arivo_recording_consent_v2"
    private const val NOTIFICATION = 4102
    private const val RECONNECT_MIN_INTERVAL_MS = 15 * 60 * 1000L

    /** Reboot path: prompt once per boot when enrolled. */
    fun request(context: Context) {
        if (!canPrompt(context)) return
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val prefs = context.getSharedPreferences("arivo_startup_consent", Context.MODE_PRIVATE)
        if (boot >= 0 && prefs.getInt("requested_boot", -2) == boot) return
        prefs.edit().putInt("requested_boot", boot).apply()
        showPrompt(context)
    }

    /**
     * Watchdog path: if the user had screen capture enabled but the session is no
     * longer running (process was killed without a reboot), surface the consent
     * prompt again, rate-limited so it cannot nag.
     */
    fun requestReconnect(context: Context) {
        AppConfig.initialize(context)
        if (!canPrompt(context)) return
        if (!captureWasEnabled(context)) return
        val prefs = context.getSharedPreferences("arivo_startup_consent", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_reconnect_prompt", 0L)
        if (now - last < RECONNECT_MIN_INTERVAL_MS) return
        prefs.edit().putLong("last_reconnect_prompt", now).apply()
        showPrompt(context)
    }

    fun opened(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }

    private fun canPrompt(context: Context): Boolean =
        AppConfig.DEVICE_KEY.isNotBlank() &&
            !ScreenCaptureService.isCapturing &&
            !ScreenCaptureConsent.isPending()

    private fun captureWasEnabled(context: Context): Boolean {
        val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else context
        return storage.getSharedPreferences("arivo_monitor_state", Context.MODE_PRIVATE)
            .getBoolean("screen_capture_enabled", false)
    }

    private fun showPrompt(context: Context) {
        val intent = Intent(context, ProjectionPermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_HIGH is required for a full-screen intent to launch over
            // the foreground app.
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Screen recording permission",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        val notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        if (notificationsAllowed) {
            val pending = PendingIntent.getActivity(
                context, NOTIFICATION, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                NOTIFICATION,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle("Arivo screen recording")
                    .setContentText("Tap to resume screen sharing.")
                    .setContentIntent(pending)
                    .setFullScreenIntent(pending, true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .build()
            )
        }

        // A full-screen intent surfaces the consent screen over other apps on most
        // builds; the direct start is a best-effort fallback that Android may block
        // without throwing. No bypass of the consent dialog is attempted.
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Log.i("ArivoMonitor", "Startup consent surfaced via notification")
        }
    }
}
