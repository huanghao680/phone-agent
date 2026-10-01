package com.phoneagent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.Shell
import java.io.File
import kotlin.concurrent.thread

/** Shared state between OpencodeWebService and OpencodeWebActivity. */
object OpencodeState {
    @Volatile var serverReady: Boolean = false
    @Volatile var lastError: String? = null

    /** opencode serve is loopback-only without a token; the WebView can load it directly. */
    const val URL: String = "http://127.0.0.1:4096/"
}

/**
 * Foreground service hosting the opencode web UI (`opencode serve`, Bun single-file
 * executable launched via the Alpine musl loader, 127.0.0.1:4096). The binary is
 * staged from assets by BinaryAgents.ensure.
 *
 * Bun stalls silently in the app's own SELinux domain (untrusted_app_27 — memory
 * management restrictions, no avc denials), but runs fine in the su domain, so on
 * rooted devices the serve process is spawned via `su -c` instead.
 */
class OpencodeWebService : Service() {

    private var proc: Process? = null
    private var spawnedViaRoot = false

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notifications.ID_OPENCODE, Notifications.build(this, "正在启动 opencode…"))
        OpencodeState.serverReady = false
        OpencodeState.lastError = null
        thread(name = "opencode-boot") {
            appendLog("=== boot start ===")
            boot()
        }
        return START_STICKY
    }

    private fun notify(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        mgr.notify(Notifications.ID_OPENCODE, Notifications.build(this, text))
    }

    private fun boot() {
        try {
            if (!NodeRuntime.isRuntimeExtracted(this)) {
                notify("正在解压 Node 运行时…")
                NodeRuntime.extractRuntime(this)
            }
            appendLog("[1] runtime ok")
            NodeRuntime.copyPackages(this)
            appendLog("[2] assets staged")
            NodeRuntime.ensureSandboxTools(this)
            NodeRuntime.seedAgentInstructions(this)
            appendLog("[3] sandbox/instructions ok")
            if (!BinaryAgents.isReady(this, BinaryAgents.OPENCODE)) {
                notify("正在解压 opencode…")
                if (!BinaryAgents.ensure(this, BinaryAgents.OPENCODE) { line -> appendLog(line) }) {
                    OpencodeState.lastError = "opencode 解压失败（当前构建未包含）"
                    notify(OpencodeState.lastError!!)
                    return
                }
            }
            val workDir = File(StorageAccess.defaultWorkspace(this))

            val loader = File(pkgLDLoader(this)).absolutePath
            val bin = File(NodeRuntime.pkgDir(this), "opencode").absolutePath
            // ProcessBuilder.redirectOutput(File) does not reliably redirect
            // fd 1/2 on this Android version (fd inspection showed sockets
            // instead of the target file). Shell redirection is reliable.
            val serveLog = File(cacheDir, "opencode-serve.log")
            serveLog.delete()

            // Bun stalls in the app's own SELinux domain; the same binary runs
            // fine as root. Probe root only when we actually need it so a
            // device without su is never prompted here.
            val rootMode = runCatching {
                Shell.getShell()
                RootIntegration.hasRoot()
            }.getOrDefault(false)

            val p: Process
            if (rootMode) {
                appendLog("[4] spawning serve via su (root domain)")
                killStaleServe()
                // Env goes inline as exports: `su` on some implementations
                // sanitizes the caller's environment. Serve argv ("opencode
                // serve --port 4096") doubles as the stale-process pattern.
                val serveCmd = buildString {
                    if (workDir.isDirectory) append("cd '").append(workDir.absolutePath).append("'; ")
                    NodeRuntime.environment(this@OpencodeWebService).forEach { (k, v) ->
                        // serve needs no proxy: Bun's HTTP server stalls when one
                        // is configured (it routes its own listener through it)
                        if (k.endsWith("_PROXY")) return@forEach
                        if (v.none { it == ' ' || it == '\'' || it == ';' || it == '$' }) {
                            append("export ").append(k).append("='").append(v).append("'; ")
                        }
                    }
                    // Bun needs a writable temp dir (no /tmp on Android) and the
                    // GNU C++ runtime next to the musl loader
                    append("export TMPDIR='").append(cacheDir.absolutePath).append("'; ")
                    append("export LD_LIBRARY_PATH='").append(NodeRuntime.pkgDir(this@OpencodeWebService).absolutePath).append("'; ")
                    append("exec '").append(loader).append("' '").append(bin).append("'")
                    append(" serve --port 4096 --hostname 127.0.0.1 --print-logs")
                    append(" > '").append(serveLog.absolutePath).append("' 2>&1")
                }
                p = ProcessBuilder("su", "-c", serveCmd).apply { redirectErrorStream(true) }.start()
                spawnedViaRoot = true
            } else {
                appendLog("[4] spawning serve in app domain")
                val args = listOf(loader, bin, "serve", "--port", "4096", "--hostname", "127.0.0.1", "--print-logs")
                val pb = ProcessBuilder(
                    "/system/bin/sh", "-c",
                    "exec " + args.joinToString(" ") + " > " + serveLog.absolutePath + " 2>&1",
                )
                if (workDir.isDirectory) pb.directory(workDir)
                val env = NodeRuntime.environment(this).toMutableMap()
                env["TMPDIR"] = cacheDir.absolutePath
                env["LD_LIBRARY_PATH"] = NodeRuntime.pkgDir(this).absolutePath
                pb.environment().putAll(env)
                p = pb.start()
            }
            appendLog("[5] serve spawned")
            proc = p
            // su client errors (denial, daemon unreachable) print to stderr —
            // capture them, otherwise a failed root spawn is fully silent
            thread(name = "opencode-su-log") {
                p.inputStream.bufferedReader().forEachLine { line -> appendLog("[su] $line") }
            }
            notify("等待 opencode 就绪…")
            val ready = awaitHttpReady(timeoutMs = 60_000)
            if (ready) {
                OpencodeState.serverReady = true
                notify("opencode 运行中")
            } else {
                val tail = runCatching {
                    serveLog.readText().trim().lineSequence().lastOrNull()?.take(160)
                }.getOrNull()
                OpencodeState.lastError = "opencode 未在 60 秒内就绪" + (tail?.let { "：$it" } ?: "，详见 cache/opencode-serve.log") +
                    (if (!rootMode) "（已知限制：Bun 无法在应用的 SELinux 域内运行，Root 设备会自动改用 root 域启动）" else "")
                notify(OpencodeState.lastError!!)
                p.destroy()
            }
        } catch (e: Exception) {
            OpencodeState.lastError = e.message ?: e.javaClass.simpleName
            notify("opencode 启动异常：${OpencodeState.lastError}")
        }
    }

    private fun pkgLDLoader(ctx: Context): String = File(NodeRuntime.pkgDir(ctx), "ld-musl-aarch64.so.1").absolutePath

    /**
     * Kills leftover serve processes that would hold port 4096 (service
     * restarted without onDestroy, app killed). Matched on the exact serve
     * argv so opencode TUI agent sessions (… opencode run …) survive. Root
     * only — the app domain cannot signal root-owned processes anyway.
     */
    private fun killStaleServe() {
        runCatching {
            ProcessBuilder("su", "-c", STALE_SERVE_KILL).start().waitFor()
        }
    }

    /** opencode serve prints logs, not a URL; probe the HTTP endpoint instead. */
    private fun awaitHttpReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val node = NodeRuntime.nodeBin(this)
        while (System.currentTimeMillis() < deadline) {
            if (OpencodeState.lastError != null) return false
            try {
                val pb = ProcessBuilder(
                    node.absolutePath,
                    "-e",
                    "fetch('${OpencodeState.URL}').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))",
                )
                pb.environment().putAll(NodeRuntime.environment(this))
                if (pb.start().waitFor() == 0) return true
            } catch (_: Exception) {
                // retry until the deadline
            }
            Thread.sleep(1000)
        }
        return false
    }

    private fun appendLog(line: String) {
        try {
            File(cacheDir, "opencode.log").appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        if (spawnedViaRoot) {
            // su's client process dying does not necessarily kill the daemon's
            // child; terminate the serve process itself while we still can
            killStaleServe()
        }
        proc?.destroy()
        proc = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val STALE_SERVE_KILL =
            "for p in /proc/[0-9]*; do " +
                "c=\$(tr '\\000' ' ' < \$p/cmdline 2>/dev/null) || continue; " +
                "case \"\$c\" in *'opencode serve --port 4096'*) " +
                "kill \${p#/proc/} 2>/dev/null;; esac; done"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OpencodeWebService::class.java))
        }
    }
}
