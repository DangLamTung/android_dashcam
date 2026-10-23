package com.driving.hud.usbcamera

import android.app.Application
import com.driving.hud.usbcamera.util.CrashReporter
import com.driving.hud.usbcamera.util.HudLogger

/**
 * Installs the logger and the crash handler before any activity code runs, so a crash
 * during startup is still reported.
 */
class HudApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        HudLogger.init(this)
        CrashReporter.install(this)
        CrashReporter.reportPreviousRunIfCrashed(this)
        CrashReporter.beginSession(this)
    }
}
