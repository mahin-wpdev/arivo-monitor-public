package com.arivo.arivo_monitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

object AppUpdateManager {
    private const val PREFS = "arivo_app_update"
    private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var checking = false
    @Volatile private var dialogShowing = false

    fun backgroundCheck(context: Context) {
        check(context.applicationContext, null)
    }
    fun checkAndPrompt(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (checking) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!activity.isFinishing) checkAndPrompt(activity)
            }, 1000)
            return
        }
        if (now - prefs.getLong("last_check", 0L) < CHECK_INTERVAL_MS) {
            promptIfReady(activity)
            return
        }
        check(activity.applicationContext, activity)
    }

    private fun check(context: Context, activity: Activity?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (checking || now - prefs.getLong("last_check", 0L) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong("last_check", now).apply()
        checking = true

        executor.execute {
            try {
                val deviceId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                ) ?: "unknown-device"
                val connection = URL(
                    AppConfig.SERVER_BASE_URL + "/device/update?device_id=" + deviceId
                ).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("X-Arivo-Key", AppConfig.DEVICE_KEY)
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val json = JSONObject(raw)
                if (!json.optBoolean("available", false)) {
                    markCurrent(prefs)
                    return@execute
                }
                val code = json.optInt("version_code", 0)
                if (code <= BuildConfig.VERSION_CODE) {
                    markCurrent(prefs)
                    return@execute
                }
                val version = json.optString("version_name", code.toString())
                val required = json.optBoolean("required", false)
                val notes = json.optString("release_notes", "")
                val expectedSha = json.optString("sha256", "")
                prefs.edit()
                    .putString("status", "downloading")
                    .putInt("progress", 0)
                    .putString("latest_version", version)
                    .putInt("latest_build", code)
                    .putString("release_notes", notes)
                    .apply()
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apk = File(dir, "arivo-$code.apk")

                if (apk.exists() && expectedSha.isNotBlank() && sha256(apk) != expectedSha) {
                    apk.delete()
                }
                if (!apk.exists()) {
                    download(context, apk, expectedSha)
                }
                if (!apk.exists()) return@execute

                prefs.edit()
                    .putInt("version_code", code)
                    .putString("version_name", version)
                    .putBoolean("required", required)
                    .putString("apk_path", apk.absolutePath)
                    .apply()

                if (activity != null) {
                    Handler(Looper.getMainLooper()).post {
                        if (!activity.isFinishing) promptIfReady(activity)
                    }
                }
            } catch (_: Exception) {
                prefs.edit()
                    .putString("status", "failed")
                    .putLong("last_check", 0L)
                    .apply()
                if (activity != null) {
                    Handler(Looper.getMainLooper()).post {
                        if (!activity.isFinishing) promptIfReady(activity)
                    }
                }
            } finally {
                checking = false
            }
        }
    }

    private fun markCurrent(prefs: SharedPreferences) {
        val stalePath = prefs.getString("apk_path", null)
        if (!stalePath.isNullOrBlank()) {
            runCatching { File(stalePath).delete() }
        }
        prefs.edit()
            .putString("status", "current")
            .putInt("progress", 100)
            .remove("version_code")
            .remove("version_name")
            .remove("required")
            .remove("apk_path")
            .remove("release_notes")
            .remove("latest_version")
            .remove("latest_build")
            .apply()
    }

    private fun download(context: Context, apk: File, expectedSha: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tmp = File(apk.parentFile, apk.name + ".part")
        val connection = URL(AppConfig.SERVER_BASE_URL + "/device/update-apk")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        connection.setRequestProperty("X-Arivo-Key", AppConfig.DEVICE_KEY)
        val total = connection.contentLengthLong
        var downloaded = 0L
        var lastProgress = -1
        connection.inputStream.use { input ->
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    if (total > 0) {
                        val progress = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                        if (progress >= lastProgress + 5 || progress == 100) {
                            lastProgress = progress
                            prefs.edit().putInt("progress", progress).apply()
                        }
                    }
                }
            }
        }
        connection.disconnect()
        if (expectedSha.isNotBlank() && sha256(tmp) != expectedSha) {
            tmp.delete()
            prefs.edit().putString("status", "failed").putInt("progress", 0).apply()
            return
        }
        if (apk.exists()) apk.delete()
        tmp.renameTo(apk)
        prefs.edit().putString("status", "downloaded").putInt("progress", 100).apply()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun promptIfReady(activity: Activity) {
        if (dialogShowing) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = prefs.getInt("version_code", 0)
        if (code <= BuildConfig.VERSION_CODE) return
        val apk = File(prefs.getString("apk_path", "") ?: "")
        if (!apk.exists()) return
        val version = prefs.getString("version_name", code.toString()) ?: code.toString()
        val required = prefs.getBoolean("required", false)
        val notes = prefs.getString("release_notes", "") ?: ""
        dialogShowing = true

        val message = buildString {
            append("Arivo v").append(version).append(" is ready to install.")
            if (notes.isNotBlank()) append("\n\n").append(notes)
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle("App update available")
            .setMessage(message)
            .setPositiveButton("Install") { _, _ ->
                dialogShowing = false
                prefs.edit().putString("status", "installing").apply()
                install(activity, apk)
            }
        if (!required) {
            builder.setNegativeButton("Later") { _, _ -> dialogShowing = false }
        }
        builder.setCancelable(!required)
        builder.setOnDismissListener { dialogShowing = false }
        builder.show()
    }
    private fun install(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.packageName)
            )
            activity.startActivity(intent)
            return
        }

        val uri = FileProvider.getUriForFile(
            activity,
            activity.packageName + ".updates",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
