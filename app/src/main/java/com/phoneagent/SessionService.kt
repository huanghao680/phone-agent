package com.phoneagent

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Foreground keep-alive so the system is less eager to reap the terminal session. */
class SessionService : Service() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val agent = intent?.getStringExtra("agent") ?: SessionHolder.pendingAgent
        val label = when (agent) {
            "dsh" -> "DeepSeek Harness 会话运行中"
            else -> "Zcode 会话运行中"
        }
        startForeground(Notifications.ID_SESSION, Notifications.build(this, label))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
