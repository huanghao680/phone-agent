package com.phoneagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifications {
    const val CHANNEL = "phone_agent"
    const val CHANNEL_DONE = "phone_agent_done"
    const val ID_SESSION = 1
    const val ID_DSH = 2
    const val ID_ZCODE_WEB = 3
    const val ID_SESSION_DONE = 10

    fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone Agent", NotificationManager.IMPORTANCE_LOW)
        )
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "会话结束", NotificationManager.IMPORTANCE_DEFAULT)
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

    /** Session-finished notice; tapping reopens the terminal to resume. */
    fun sessionFinished(ctx: Context, agent: String, exitStatus: Int) {
        ensureChannel(ctx)
        val intent = Intent(ctx, TerminalPickerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, ID_SESSION_DONE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("$agent 会话已结束")
            .setContentText(if (exitStatus == 0) "正常退出。点击回到终端。" else "退出码 $exitStatus。点击回到终端。")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(Context.NOTIFICATION_SERVICE)
            .let { it as NotificationManager }
            .notify(ID_SESSION_DONE, n)
    }
}
