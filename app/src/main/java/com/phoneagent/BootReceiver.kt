package com.phoneagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings back the engines that were running before the device rebooted. Only
 * acts when the user enabled auto-restore in settings; a stale or broken
 * install is handled by the watchdog rather than by us retrying here.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val n = EngineState.restoreAll(ctx)
        Log.i("phone-agent-boot", "restored $n engine(s)")
    }
}
