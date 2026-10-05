package com.arivo.arivo_monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MonitorDiagnostics {
    private const val PREFS = "arivo_diagnostics"
    private const val ACTIVE = "monitor_service_active"
    private const val EVENTS = "pending_offline_events"

    fun serviceStarted(context: Context, snapshot: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ACTIVE, false)) {
            record(context, "monitor_service_restarted", snapshot)
        }
        prefs.edit().putBoolean(ACTIVE, true).apply()
    }

    fun serviceStopped(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ACTIVE, false).apply()
    }

    @Synchronized
    fun record(context: Context, reason: String, snapshot: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val events = try {
            JSONArray(prefs.getString(EVENTS, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val previous = events.optJSONObject(events.length() - 1)
        if (previous?.optString("reason") == reason) return
        events.put(JSONObject()
            .put("at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()))
            .put("reason", reason.take(80))
            .put("context", snapshot))
        val bounded = JSONArray()
        for (i in (events.length() - 20).coerceAtLeast(0) until events.length()) {
            bounded.put(events.getJSONObject(i))
        }
        prefs.edit().putString(EVENTS, bounded.toString()).apply()
    }

    fun pending(context: Context): JSONArray? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(EVENTS, "[]") ?: "[]"
        return try {
            JSONArray(raw).takeIf { it.length() > 0 }
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(EVENTS).apply()
    }
}
