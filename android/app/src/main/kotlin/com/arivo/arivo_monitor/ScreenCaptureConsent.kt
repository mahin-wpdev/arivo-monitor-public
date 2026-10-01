package com.arivo.arivo_monitor

import java.util.concurrent.atomic.AtomicBoolean

// Deduplicate consent requests across the main and boot-recovery activities.
// A fresh MediaProjection session still uses Android's consent dialog.
object ScreenCaptureConsent {
    private val pending = AtomicBoolean(false)
    fun isPending(): Boolean = pending.get()
    fun begin(): Boolean = !ScreenCaptureService.isCapturing && pending.compareAndSet(false, true)
    fun complete() { pending.set(false) }
}
