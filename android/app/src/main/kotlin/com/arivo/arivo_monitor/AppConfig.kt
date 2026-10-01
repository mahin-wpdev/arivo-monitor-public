package com.arivo.arivo_monitor

import android.content.Context
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AppConfig {
    val SERVER_BASE_URL: String = BuildConfig.ARIVO_SERVER_BASE_URL
    @Volatile private var token = ""
    @Volatile private var enrolledId = ""
    fun deviceId(context: Context): String = enrolledId.ifBlank {
        android.provider.Settings.Secure.getString(context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID) ?: "unknown-device"
    }
    val DEVICE_KEY: String get() = token
    private const val KEY_ALIAS = "arivo_device_activation"

    @Synchronized
    fun initialize(context: Context) {
        if (!(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked) return
        if (token.isNotBlank()) return
        token = runCatching {
            val file = File(context.noBackupFilesDir, "activation.json")
            if (!file.exists()) return@runCatching ""
            val saved = JSONObject(file.readText())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, storageKey(), GCMParameterSpec(128,
                Base64.decode(saved.getString("iv"), Base64.NO_WRAP)))
            val decoded = String(cipher.doFinal(Base64.decode(saved.getString("value"), Base64.NO_WRAP)), Charsets.UTF_8)
            val value = if (decoded.startsWith("{")) {
                val activation = JSONObject(decoded)
                val id = activation.getString("device_id")
                require(id.matches(Regex("[a-f0-9]{32}")))
                enrolledId = id
                activation.getString("token")
            } else decoded
            value.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: ""
        }.getOrDefault("")
    }

    @Synchronized
    fun saveToken(context: Context, value: String, deviceId: String = enrolledId) {
        require(value.matches(Regex("[a-f0-9]{64}")))
        require(deviceId.isBlank() || deviceId.matches(Regex("[a-f0-9]{32}")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, storageKey())
        val plaintext = if (deviceId.isBlank()) value else
            JSONObject().put("token", value).put("device_id", deviceId).toString()
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val saved = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("value", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        val temporary = File(context.noBackupFilesDir, "activation.tmp")
        temporary.writeText(saved.toString())
        check(temporary.renameTo(File(context.noBackupFilesDir, "activation.json")))
        enrolledId = deviceId
        token = value
    }

    private fun storageKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    const val HEARTBEAT_SECONDS = 60L
    const val LOCATION_MIN_TIME_MS = 30000L
    const val LOCATION_MIN_DISTANCE_M = 10f
    const val LOCATION_MOVING_UPLOAD_MS = 45000L
    const val LOCATION_STATIONARY_UPLOAD_MS = 180000L
    const val SCREENSHOT_INTERVAL_MS = 60000L
    const val LIVE_FRAME_INTERVAL_MS = 1000L
}
