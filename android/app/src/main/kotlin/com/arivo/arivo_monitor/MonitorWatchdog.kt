package com.arivo.arivo_monitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Keeps [MonitorService] alive on aggressive OEM builds (Vivo/Funtouch, iQOO, etc.)
 * that silently kill background foreground services.
 *
 * Two independent, permission-free restart paths are used so that if the OEM
 * suppresses one, the other can still recover the service:
 *   - a periodic [WatchdogJobService] (JobScheduler), and
 *   - a self-rescheduling [AlarmManager] alarm (inexact + allow-while-idle, so no
 *     SCHEDULE_EXACT_ALARM permission is required).
 *
 * Neither can defeat a user-forced stop, but both recover from routine
 * memory-pressure / Doze kills without the user reopening the app.
 */
object MonitorWatchdog {
    private const val TAG = "ArivoWatchdog"
    private const val JOB_ID = 7701
    private const val ALARM_REQUEST = 7702
    private const val INTERVAL_MS = 15 * 60 * 1000L // 15 min (JobScheduler minimum)

    fun schedule(context: Context) {
        scheduleJob(context)
        scheduleAlarm(context)
    }

    /** Starts [MonitorService] again if it is not currently running. */
    fun ensureServiceRunning(context: Context) {
        if (MonitorService.isRunning) return
        AppConfig.initialize(context)
        if (AppConfig.DEVICE_KEY.isBlank()) return
        val service = Intent(context, MonitorService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        } catch (error: Exception) {
            // Android forbids background foreground-service starts in some states;
            // the next alarm/job tick retries once the app is allowed to start it.
            Log.w(TAG, "Service restart deferred: ${error.javaClass.simpleName}")
        }
    }

    private fun scheduleJob(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            ?: return
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, WatchdogJobService::class.java)
        )
            .setPersisted(true)
            .setPeriodic(INTERVAL_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .build()
        try {
            scheduler.schedule(job)
        } catch (error: Exception) {
            Log.w(TAG, "Job schedule failed: ${error.javaClass.simpleName}")
        }
    }

    private fun scheduleAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return
        val intent = Intent(context, WatchdogAlarmReceiver::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val pending = PendingIntent.getBroadcast(context, ALARM_REQUEST, intent, flags)
        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending
                )
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Alarm schedule failed: ${error.javaClass.simpleName}")
        }
    }
}

/** Periodic JobScheduler tick: revive the monitor service if it has been killed. */
class WatchdogJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        MonitorWatchdog.ensureServiceRunning(applicationContext)
        // JobScheduler keeps the periodic job registered on its own; return false
        // because the (re)start work completes synchronously here.
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}

/** AlarmManager tick: revive the service and re-arm the next alarm. */
class WatchdogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        MonitorWatchdog.ensureServiceRunning(context.applicationContext)
        // Inexact allow-while-idle alarms are one-shot, so chain the next one.
        MonitorWatchdog.schedule(context.applicationContext)
    }
}
