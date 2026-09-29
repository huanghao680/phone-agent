package com.phoneagent

import android.app.Application
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
    }
}
