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
    private var spawnedViaShizuku = false

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
            val servePidFile = File(cacheDir, "opencode-serve.pid")
            serveLog.delete()
            servePidFile.delete()

            // Debug hooks for diagnosing the app-domain Bun stall without a
            // new APK: cache/oc-serve-env.txt (KEY=VALUE lines) overrides the
            // spawn environment; cache/oc-force-app-domain skips the su path
            // so the stall is reproducible on rooted devices too.
            val forceAppDomain = File(cacheDir, "oc-force-app-domain").exists()
            val envOverrides = mutableMapOf<String, String>()
            runCatching {
                val envFile = File(cacheDir, "oc-serve-env.txt")
                if (envFile.exists()) {
                    envFile.readLines().forEach { line ->
                        val t = line.trim()
                        val i = t.indexOf('=')
                        if (t.isNotEmpty() && !t.startsWith("#") && i > 0) {
                            envOverrides[t.substring(0, i).trim()] = t.substring(i + 1).trim()
                        }
                    }
                }
            }
            if (envOverrides.isNotEmpty()) {
                appendLog("[env] overrides: $envOverrides")
            }

            // Bun dies instantly (SIGSYS, seccomp) when the app spawns it, but
            // runs fine from root and from run-as (adbd/adb-spawned processes
            // skip zygote's seccomp filter). Probe root only when we need it so
            // a device without su is never prompted here.
            val rootMode = !forceAppDomain && runCatching {
                Shell.getShell()
                RootIntegration.hasRoot()
            }.getOrDefault(false)
            val shizukuMode = !rootMode && ShizukuHelper.granted()

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
                    envOverrides.forEach { (k, v) ->
                        append("export ").append(k).append("='").append(v).append("'; ")
                    }
                    // Bun needs a writable temp dir (no /tmp on Android) and the
                    // GNU C++ runtime next to the musl loader
                    append("export TMPDIR='").append(cacheDir.absolutePath).append("'; ")
                    append("export LD_LIBRARY_PATH='").append(NodeRuntime.pkgDir(this@OpencodeWebService).absolutePath).append("'; ")
                    append("echo $$ > '").append(servePidFile.absolutePath).append("'; ")
                    append("exec '").append(loader).append("' '").append(bin).append("'")
                    append(" serve --port 4096 --hostname 127.0.0.1 --print-logs")
                    append(" > '").append(serveLog.absolutePath).append("' 2>&1")
                }
                p = ProcessBuilder("su", "-c", serveCmd).apply { redirectErrorStream(true) }.start()
                spawnedViaRoot = true
            } else if (shizukuMode) {
                appendLog("[4] spawning serve via Shizuku (shell uid + run-as)")
                killStaleServeViaRunAs()
                // Same inline-export command as the su path, but everything the
                // serve process must WRITE lives under /data/local/tmp: run-as
                // only changes the uid, the SELinux domain stays shell, and the
                // shell domain may read but not write app data. The existing
                // config is copied over so the webui keeps the user's settings.
                val ocHome = "/data/local/tmp/oc-home"
                val ocTmp = "/data/local/tmp/oc-tmp"
                val ocLog = "$ocTmp/serve.log"
                val serveCmd = buildString {
                    append("mkdir -p '").append(ocHome).append("' '").append(ocTmp).append("'; ")
                    append("cp -r '").append(NodeRuntime.homeDir(this@OpencodeWebService).absolutePath)
                        .append("/.config' '").append(ocHome).append("/' 2>/dev/null; ")
                    if (workDir.isDirectory) append("cd '").append(workDir.absolutePath).append("' 2>/dev/null; ")
                    NodeRuntime.environment(this@OpencodeWebService).forEach { (k, v) ->
                        if (k.endsWith("_PROXY") || k == "HOME" || k == "TMPDIR" || k == "LD_LIBRARY_PATH") return@forEach
                        if (v.none { it == ' ' || it == '\'' || it == ';' || it == '$' }) {
                            append("export ").append(k).append("='").append(v).append("'; ")
                        }
                    }
                    envOverrides.forEach { (k, v) ->
                        append("export ").append(k).append("='").append(v).append("'; ")
                    }
                    append("export HOME='").append(ocHome).append("'; ")
                    append("export TMPDIR='").append(ocTmp).append("'; ")
                    append("export LD_LIBRARY_PATH='").append(NodeRuntime.pkgDir(this@OpencodeWebService).absolutePath).append("'; ")
                    append("echo $$ > '").append(ocTmp).append("/serve.pid'; ")
                    append("exec '").append(loader).append("' '").append(bin).append("'")
                    append(" serve --port 4096 --hostname 127.0.0.1 --print-logs")
                    append(" > '").append(ocLog).append("' 2>&1")
                }
                p = ShizukuHelper.sh("run-as " + packageName + " sh -c '" + serveCmd + "'")
                spawnedViaShizuku = true
            } else {
                appendLog("[4] spawning serve in app domain")
                val args = listOf(loader, bin, "serve", "--port", "4096", "--hostname", "127.0.0.1", "--print-logs")
                val pb = ProcessBuilder(
                    "/system/bin/sh", "-c",
                    "echo $$ > '" + servePidFile.absolutePath + "'; setsid " + args.joinToString(" ") +
                        " < /dev/null > " + serveLog.absolutePath + " 2>&1; " +
                        "echo $? > '" + File(cacheDir, "opencode-serve.rc").absolutePath + "'",
                )
                if (workDir.isDirectory) pb.directory(workDir)
                val env = NodeRuntime.environment(this).toMutableMap()
                env["TMPDIR"] = cacheDir.absolutePath
                env["LD_LIBRARY_PATH"] = NodeRuntime.pkgDir(this).absolutePath
                env.putAll(envOverrides)
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
            val ready = awaitHttpReady(timeoutMs = 60_000, child = p)
            if (ready) {
                OpencodeState.serverReady = true
                notify("opencode 运行中")
            } else {
                dumpServeDiagnostics(p)
                val tail = runCatching {
                    serveLog.readText().trim().lineSequence().lastOrNull()?.take(160)
                }.getOrNull()
                OpencodeState.lastError = "opencode 未在 60 秒内就绪" + (tail?.let { "：$it" } ?: "，详见 cache/opencode-serve.log") +
                    (if (!rootMode) "（已知限制：Android 给应用进程装的 seccomp 过滤器会拒绝 Bun 需要的系统调用，子进程直接 Bad system call(SIGSYS) 退出；Root 设备自动改用 root 域启动，非 Root 请用终端里的 opencode TUI）" else "")
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
     * Dumps the stalled serve child's kernel-side state into opencode.log.
     * The service is the child's direct parent (same uid), so /proc reads are
     * permitted where an external run-as shell would be denied across SELinux
     * domains. This is how we see which syscall Bun is stuck in.
     */
    private fun dumpServeDiagnostics(p: Process) {
        runCatching {
            val exit = runCatching { p.exitValue() }.getOrNull()
            appendLog("[diag] alive=${p.isAlive} exitValue=$exit")
            runCatching {
                val rcF = File(cacheDir, "opencode-serve.rc")
                if (rcF.exists()) appendLog("[diag] shell rc=" + rcF.readText().trim())
                else appendLog("[diag] shell rc file absent (still running?)")
            }
            // Bun exits immediately (before any log) when serve cannot start;
            // the exit code is the only signal we get, so report it up front
            if (exit != null) {
                appendLog("[diag] serve exited with code $exit")
                val logBytes = File(cacheDir, "opencode-serve.log").length()
                appendLog("[diag] serve log bytes=$logBytes")
                return
            }
            val pidFile = File(cacheDir, "opencode-serve.pid")
            if (!pidFile.exists()) return
            val pid = pidFile.readText().trim().toIntOrNull() ?: return
            val proc = File("/proc/$pid")
            val status = File(proc, "status").readText()
            appendLog("[diag] " + status.lineSequence()
                .filter { it.startsWith("Name") || it.startsWith("State") || it.startsWith("Threads") || it.startsWith("VmRSS") || it.startsWith("VmSize") }
                .joinToString(" | "))
            val tasks = File(proc, "task").listFiles().orEmpty()
            appendLog("[diag] threads=${tasks.size}")
            tasks.take(40).forEach { t ->
                val stat = runCatching { File(t, "stat").readText() }.getOrNull()
                val state = stat?.substringAfterLast(')')?.trim()?.substringBefore(' ') ?: "?"
                val wchan = runCatching { File(t, "wchan").readText().trim() }.getOrNull() ?: "?"
                val syscall = runCatching { File(t, "syscall").readText().trim().substringBefore(' ') }.getOrNull() ?: "?"
                appendLog("[diag]   tid=${t.name} state=$state wchan=$wchan syscall=$syscall")
            }
            runCatching {
                val maps = File(proc, "maps").readLines()
                val execSegs = maps.count { it.contains('x') }
                val anonExec = maps.count { it.contains('x') && !it.contains('/') }
                appendLog("[diag] maps=${maps.size} execSegs=$execSegs anonExec=$anonExec")
            }
            runCatching {
                val fds = File(proc, "fd").listFiles().orEmpty()
                appendLog("[diag] fds=${fds.size}")
                fds.take(30).forEach { fd ->
                    val target = runCatching { java.nio.file.Files.readSymbolicLink(fd.toPath()).toString() }.getOrDefault("?")
                    appendLog("[diag]   fd=${fd.name} -> $target")
                }
            }
        }.onFailure { appendLog("[diag] dump failed: ${it.message}") }
    }

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

    /**
     * Same scan without root, for the Shizuku spawn path: the scan runs
     * through Shizuku's shell (shell uid can read any /proc cmdline and kill
     * app processes, like adb shell can).
     */
    private fun killStaleServeViaRunAs() {
        runCatching {
            ShizukuHelper.sh(STALE_SERVE_KILL).waitFor()
        }
    }

    /** opencode serve prints logs, not a URL; probe the HTTP endpoint instead. */
    private fun awaitHttpReady(timeoutMs: Long, child: Process? = null): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val node = NodeRuntime.nodeBin(this)
        while (System.currentTimeMillis() < deadline) {
            if (OpencodeState.lastError != null) return false
            // the child dying means serve gave up (e.g. port already in use);
            // no point polling the port for the rest of the timeout
            if (child != null && !child.isAlive) {
                appendLog("[wait] serve child exited, aborting wait")
                return false
            }
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
        } else if (spawnedViaShizuku) {
            // same idea: Shizuku's wrapper is shell-uid, its child ours —
            // sweep by argv in case the wrapper death orphaned it
            killStaleServeViaRunAs()
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
