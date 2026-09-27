package com.phoneagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/** Foreground keep-alive so the system is less eager to reap the terminal session. */
class SessionService : Service() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notifications.ID_SESSION, Notifications.build(this, "Zcode 会话运行中"))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

object Notifications {
    const val CHANNEL = "phone_agent"
    const val ID_SESSION = 1
    const val ID_DSH = 2

    fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone Agent", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun build(ctx: Context, text: String): Notification {
        ensureChannel(ctx)
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Phone Agent")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }
}
