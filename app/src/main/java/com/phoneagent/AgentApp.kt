package com.phoneagent

import android.app.Application
import android.content.Intent
import android.webkit.WebView
import com.topjohnwu.superuser.Shell

class AgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(15)
        )
        // chrome://inspect over adb for the webUI WebViews; debug builds only
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        Thread({
            // drop handover files older than a week (cheap: few files, no I/O storm)
            try {
                FileIncomingActivity.sweepExpired(this)
            } catch (_: Exception) {
            }
            // loopback tool channel for agent sessions
            try {
                startForegroundService(Intent(this, PhoneToolService::class.java))
            } catch (_: Exception) {
            }
        }, "app-boot").start()
    }
}
