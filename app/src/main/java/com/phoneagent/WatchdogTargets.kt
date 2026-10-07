package com.phoneagent

import android.content.Context

/**
 * Starts battery/health watching for whichever web service an activity just
 * launched. Idempotent: the watchdog keeps a single tick thread and quietly
 * ignores targets already registered.
 */
object WatchdogTargets {

    fun watchZcode(a: Context) {
        ensure(
            a, listOf(
                Watchdog.Target(
                    "zcode", 3030, "Zcode Web", ZcodeWebService::class.java,
                ) { ZcodeWebState.process },
            ),
        )
    }

    fun watchDsh(a: Context) {
        ensure(
            a, listOf(
                Watchdog.Target(
                    "dsh", 3080, "DeepSeek Harness", DshService::class.java,
                ) { DshState.process },
            ),
        )
    }

    fun watchOpencode(a: Context) {
        ensure(
            a, listOf(
                Watchdog.Target(
                    "opencode", 4096, "opencode", OpencodeWebService::class.java,
                ) { OpencodeState.process },
            ),
        )
    }

    private fun ensure(a: Context, targets: List<Watchdog.Target>) {
        val ctx = a.applicationContext
        Watchdog.reset()
        Watchdog.start(ctx, targets)
    }
}
