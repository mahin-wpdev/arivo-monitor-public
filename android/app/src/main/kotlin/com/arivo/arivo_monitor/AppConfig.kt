package com.arivo.arivo_monitor

object AppConfig {
    val SERVER_BASE_URL: String = BuildConfig.ARIVO_SERVER_BASE_URL
    val DEVICE_KEY: String = BuildConfig.ARIVO_DEVICE_KEY
    const val HEARTBEAT_SECONDS = 60L
    const val LOCATION_MIN_TIME_MS = 30000L
    const val LOCATION_MIN_DISTANCE_M = 10f
    const val LOCATION_MOVING_UPLOAD_MS = 45000L
    const val LOCATION_STATIONARY_UPLOAD_MS = 180000L
    const val SCREENSHOT_INTERVAL_MS = 60000L
    const val LIVE_FRAME_INTERVAL_MS = 1000L
}
