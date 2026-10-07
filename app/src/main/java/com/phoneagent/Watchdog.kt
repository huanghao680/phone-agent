package com.phoneagent

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.concurrent.thread
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Keep-alive watchdog for the three local web services.
 *
 * Modeled on the four-state probe of dsh-mobile-apk (see docs credit in README),
 * adapted to our ports. Key idea: a dead process and a half-dead one look
 * identical from the UI, so the probe separates them before deciding anything:
 *
 *  - HEALTHY       HTTP answers.
 *  - DEGRADED_HTTP TCP port is open but HTTP no longer answers -> half-dead;
 *                  escalates to a controlled restart after GRACE ticks.
 *  - DEGRADED_LOG  HTTP answers but the log tail carries a crash signature ->
 *                  NEVER restart on its own (a running conversation is in
 *                  flight); it is reported so the user can act.
 *  - DEAD          port refuses connections -> restart immediately.
 *
 * Consecutive failures are backed off exponentially (5s -> 80s cap) and the
 * watchdog trips a breaker after MAX_CONSEC_FAILURES so a permanently broken
 * install does not spin forever. Only services we actually started are watched.
 */
object Watchdog {

    enum class State { HEALTHY, DEGRADED_HTTP, DEGRADED_LOG, DEAD }

    private const val TAG = "phone-agent-watchdog"

    /** Half-dead HTTP grace: 6 ticks x 5s = 30s before a restart is issued. */
    private const val DEGRADED_RESTART_CONFIRMATIONS = 6

    /** Back-off steps (milliseconds) after each failed probe run. */
    private val BACKOFF = longArrayOf(5_000, 10_000, 20_000, 40_000, 80_000)

    /** Beyond this many consecutive failures we stop poking and just report. */
    private const val MAX_CONSEC_FAILURES = 12

    private const val TICK_MS = 5_000L

    /** A cold web service legitimately needs this long (extract + boot). */
    private const val GRACE_MS = 90_000L

    /** Quiet period after our own restart, so we never stampede a booting service. */
    private const val RESTART_COOLDOWN_MS = 60_000L

    data class Target(
        val agent: String,
        val port: Int,
        val label: String,
        val service: Class<*>,
        /** current live process, used to distinguish "no port" from "hung" */
        val process: () -> Process?,
    )

    private data class TargetState(
        var httpMisses: Int = 0,
        var lastRestartAt: Long = 0L,
        var firstSeenAt: Long = System.currentTimeMillis(),
        /** false while we are still giving the service room to come up. */
        var everHealthy: Boolean = false,
        @Volatile var lastState: State = State.HEALTHY,
    )

    @Volatile var running = false
        private set

    @Volatile var enabled = true

    private var thread: Thread? = null
    private val states = HashMap<String, TargetState>()

    /** Extra crash signatures worth surfacing, per agent. */
    private val SIGNATURES = mapOf(
        "opencode" to listOf("Bad system call", "ServeError", "EADDRINUSE"),
        "dsh" to listOf("plugin tree failed to load", "ERR_PNPM", "Cannot find module"),
        "zcode" to listOf("EADDRINUSE", "Cannot find module"),
    )

    private val activeTargets = LinkedHashMap<String, Target>()

    /** Registers targets and makes sure the tick thread is running. Idempotent. */
    fun start(ctx: Context, targets: List<Target>) {
        val now = System.currentTimeMillis()
        val added = targets.filter { activeTargets.putIfAbsent(it.agent, it) == null }
        // a newly launched service is booting, not failing: start it in grace
        for (t in targets) {
            if (states[t.agent]?.lastRestartAt == 0L) {
                states.getOrPut(t.agent) { TargetState() }.lastRestartAt = now
            }
        }
        if (targets.isEmpty()) return
        if (running) {
            if (added.isNotEmpty()) Log.i(TAG, "targets now: ${activeTargets.keys}")
            return
        }
        running = true
        val ctxRef = ctx.applicationContext
        thread = thread(name = "watchdog", priority = Thread.MIN_PRIORITY) {
            var failures = 0
            while (running) {
                if (!enabled) {
                    sleep(TICK_MS)
                    continue
                }
                try {
                    var anyDown = false
                    var anyAlive = false
                    for (t in activeTargets.values.toList()) {
                        val st = states.getOrPut(t.agent) { TargetState() }
                        val s = probe(ctx, t)
                        st.lastState = s
                        // Until we have ever seen this service answer, it is booting,
                        // not failing. dsh/zcode need tens of seconds on a cold start
                        // (runtime extract + engine boot) — pulling the service down
                        // mid-start is what trips
                        // ForegroundServiceDidNotStartInTimeException.
                        if (!st.everHealthy) {
                            if (System.currentTimeMillis() > st.firstSeenAt + GRACE_MS) {
                                st.everHealthy = true // grace exhausted; failures now count
                            } else {
                                st.lastState = s
                                continue
                            }
                        }
                        when (s) {
                            State.HEALTHY -> {
                                anyAlive = true
                                st.httpMisses = 0
                                st.everHealthy = true
                            }
                            State.DEGRADED_HTTP -> {
                                anyDown = true
                                st.httpMisses++
                                if (st.httpMisses >= DEGRADED_RESTART_CONFIRMATIONS) {
                                    Log.w(TAG, "${t.agent}: degraded-http x${st.httpMisses} -> restart")
                                    restart(ctxRef, t, st)
                                    st.httpMisses = 0
                                }
                            }
                            State.DEGRADED_LOG -> { /* reported in the notification only; never restart */ }
                            State.DEAD -> {
                                anyDown = true
                                st.httpMisses = 0
                                Log.w(TAG, "${t.agent}: dead -> restart")
                                restart(ctxRef, t, st)
                            }
                        }
                    }
                    failures = if (anyDown && !anyAlive) failures + 1 else 0
                    if (failures >= MAX_CONSEC_FAILURES) {
                        Log.w(TAG, "breaker open after $failures failures; backing off")
                        report(ctxRef, "连续多次无法恢复，看门狗已暂停（可重新打开对应界面）")
                        enabled = false
                        failures = 0
                    }
                    val backoff = BACKOFF[(failures - 1).coerceIn(0, BACKOFF.size - 1)]
                    sleep(if (failures > 0) backoff else TICK_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "tick failed", e)
                    sleep(TICK_MS)
                }
            }
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        states.clear()
        activeTargets.clear()
    }

    /** Called after a manual (re)start so the breaker and counters reset. */
    fun reset(agent: String? = null) {
        enabled = true
        if (agent == null) states.clear() else states[agent]?.httpMisses = 0
    }

    fun stateOf(agent: String): State = states[agent]?.lastState ?: State.HEALTHY

    private fun probe(ctx: Context, t: Target): State {
        val alive = try {
            t.process()?.isAlive == true
        } catch (_: Exception) {
            false
        }
        val httpOk = httpOk(t.port)
        if (httpOk) {
            // healthy HTTP + crash signature in the log tail = report only
            val sig = tailSignature(ctx, t.agent)
            return if (sig != null) State.DEGRADED_LOG else State.HEALTHY
        }
        // no HTTP: a live process at this point is half-dead, a gone one is dead
        return if (alive) State.DEGRADED_HTTP else State.DEAD
    }

    private fun httpOk(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 800)
            s.soTimeout = 1500
            s.getOutputStream().write(
                ("GET / HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            val buf = ByteArray(64)
            val n = s.getInputStream().read(buf)
            // ANY HTTP response means the server is alive: dsh web answers 401
            // without its one-shot token, which must not read as "down"
            n > 0 && String(buf, 0, n).startsWith("HTTP/")
        }
    } catch (_: Exception) {
        false
    }

    /** Last matching line of the agent's log file, if it carries a crash signature. */
    private fun tailSignature(ctx: Context, agent: String): String? {
        val f = logFile(ctx, agent) ?: return null
        if (!f.isFile) return null
        val sigs = SIGNATURES[agent] ?: return null
        return try {
            val lines = f.readLines().takeLast(40)
            val hit = lines.lastOrNull { line -> sigs.any { line.contains(it, ignoreCase = true) } }
            hit?.take(160)
        } catch (_: Exception) {
            null
        }
    }

    private fun logFile(ctx: Context, agent: String): File? {
        val name = when (agent) {
            "zcode" -> "zcode-web.log"
            "dsh" -> "dsh.log"
            "opencode" -> "opencode.log"
            else -> return null
        }
        return File(ctx.cacheDir, name)
    }

    private fun restart(ctx: Context, t: Target, st: TargetState) {
        // cool down first: hammering startForegroundService right after stopService
        // trips ForegroundServiceDidNotStartInTimeException on modern Android
        val since = System.currentTimeMillis() - st.lastRestartAt
        if (st.lastRestartAt != 0L && since < RESTART_COOLDOWN_MS) {
            Log.i(TAG, "${t.agent}: still within cooldown (${since}ms), skip")
            return
        }
        st.lastRestartAt = System.currentTimeMillis()
        st.everHealthy = false
        st.firstSeenAt = System.currentTimeMillis()
        try {
            Notifications.notice(ctx, "${t.label} 连接异常，正在自动重启…")
            val i = android.content.Intent(ctx, t.service)
            ctx.stopService(i)
            // let the old process tear down and its ports release
            Thread.sleep(2_000)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        } catch (e: Exception) {
            Log.w(TAG, "restart ${t.agent} failed", e)
        }
    }

    private fun report(ctx: Context, msg: String) {
        Notifications.notice(ctx, msg)
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            running = false
        }
    }
}
