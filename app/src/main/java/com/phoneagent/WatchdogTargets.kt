package com.phoneagent

import android.content.Context

/**
 * Starts battery/health watching for whichever web service an activity just
 * launched. Idempotent: the watchdog keeps a single tick thread and quietly
 * ignores targets already registered.
 */
object WatchdogTargets {

    /** Registers every web agent from the registry with the watchdog. */
    fun watchAll(ctx: android.content.Context) {
        val targets = AgentRegistry.withPort().mapNotNull { a ->
            val port = a.port ?: return@mapNotNull null
            val svc = a.service ?: return@mapNotNull null
            Watchdog.Target(a.id, port, a.label, svc) {
                when (a.id) {
                    "zcode" -> ZcodeWebState.process
                    "dsh" -> DshState.process
                    else -> OpencodeState.process
                }
            }
        }
        Watchdog.reset()
        Watchdog.start(ctx, targets)
    }

    fun watchZcode(ctx: android.content.Context) {
        Watchdog.reset("zcode")
        Watchdog.start(ctx, target("zcode") ?: return)
    }

    fun watchDsh(ctx: android.content.Context) {
        Watchdog.reset("dsh")
        Watchdog.start(ctx, target("dsh") ?: return)
    }

    fun watchOpencode(ctx: android.content.Context) {
        Watchdog.reset("opencode")
        Watchdog.start(ctx, target("opencode") ?: return)
    }

    private fun target(id: String): List<Watchdog.Target> {
        val a = AgentRegistry.byId(id) ?: return emptyList()
        val port = a.port ?: return emptyList()
        val svc = a.service ?: return emptyList()
        return listOf(
            Watchdog.Target(a.id, port, a.label, svc) {
                when (a.id) {
                    "zcode" -> ZcodeWebState.process
                    "dsh" -> DshState.process
                    else -> OpencodeState.process
                }
            },
        )
    }
}
