package com.phoneagent

import android.content.Context

/**
 * Remembers which web engines the user had running, so a reboot can bring them
 * back. Written when an engine activity opens, cleared when the user leaves for
 * good (we only remember engines that were actually healthy at some point).
 */
object EngineState {

    private const val FILE = "engine-state"

    /** Records that this agent's engine is meant to be running. */
    fun markRunning(ctx: Context, agent: String) {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val set = prefs.getStringSet("running", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(agent)
        prefs.edit().putStringSet("running", set).apply()
    }

    fun markStopped(ctx: Context, agent: String) {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val set = prefs.getStringSet("running", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.remove(agent)
        prefs.edit().putStringSet("running", set).apply()
    }

    fun isRunning(ctx: Context, agent: String): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet("running", emptySet())?.contains(agent) ?: false

    fun allRunning(ctx: Context): Set<String> =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet("running", emptySet()) ?: emptySet()

    /** Restores every remembered engine. Returns how many were relaunched. */
    fun restoreAll(ctx: Context): Int {
        if (!Prefs.restoreOnBoot(ctx)) return 0
        var n = 0
        for (agent in allRunning(ctx)) {
            try {
                when (agent) {
                    "zcode" -> { ctx.startForegroundService(android.content.Intent(ctx, ZcodeWebService::class.java)); WatchdogTargets.watchZcode(ctx); n++ }
                    "dsh" -> { ctx.startForegroundService(android.content.Intent(ctx, DshService::class.java)); WatchdogTargets.watchDsh(ctx); n++ }
                    "opencode" -> { OpencodeWebService.start(ctx); n++ }
                }
            } catch (_: Exception) {
            }
        }
        return n
    }
}
