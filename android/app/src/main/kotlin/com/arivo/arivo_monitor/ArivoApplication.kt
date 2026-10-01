package com.arivo.arivo_monitor

import android.app.Application

class ArivoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppConfig.initialize(this)
    }
}
