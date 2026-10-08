package com.phoneagent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.io.File
import kotlin.concurrent.thread

/** Shared state between DshService and DshActivity. */
object DshState {
    @Volatile var serverReady: Boolean = false
    @Volatile var lastError: String? = null

    /**
     * dsh prints a one-shot authenticated URL (`http://127.0.0.1:3080/?token=...`)
     * and rejects unauthenticated requests, so the WebView must load this URL.
     */
    @Volatile var startUrl: String? = null

    /** Live process handle, published for the watchdog's dead/hung distinction. */
    @Volatile var process: Process? = null
}

/**
 * Foreground service hosting the DeepSeek Harness web server (embedded Node,
 * 127.0.0.1:3080). First run also performs runtime extraction and CLI install.
 */
class DshService : Service() {

    private val TOKEN_RE = Regex("http://127\\.0\\.0\\.1:\\d+/\\?token=[A-Za-z0-9_-]+")

    private var proc: Process? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
    }

    @Volatile private var booting = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notifications.ID_DSH, Notifications.build(this, "正在启动 DeepSeek Harness…"))
        // EngineState.restoreAll (app onCreate) and the Activity both start this
        // service within the same second; two concurrent boots double every
        // patch/check and race on the same files. A boot in progress wins.
        if (booting || proc?.isAlive == true) return START_STICKY
        DshState.serverReady = false
        DshState.lastError = null
        booting = true
        thread(name = "dsh-boot") {
            try {
                boot()
            } finally {
                booting = false
            }
        }
        return START_STICKY
    }

    private fun notify(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        mgr.notify(Notifications.ID_DSH, Notifications.build(this, text))
    }

    private fun boot() = boot(retryAfterRevert = false)

    private fun boot(retryAfterRevert: Boolean) {
        // phase timing goes to logcat (dsh-boot): cold start used to be a black
        // box; the breakdown is how we find out what to optimize next
        val t0 = android.os.SystemClock.uptimeMillis()
        fun mark(what: String) {
            android.util.Log.i("dsh-boot", "$what at ${android.os.SystemClock.uptimeMillis() - t0}ms")
        }
        mark("boot begin")
        try {
            if (!NodeRuntime.isRuntimeExtracted(this)) {
                notify("正在解压 Node 运行时…")
                NodeRuntime.extractRuntime(this)
            }
            mark("runtime extracted")
            // bwrap shim + sandbox-mode file: the web GUI itself runs
            // danger-full-access, but headless/CLI profiles spawned from
            // sessions inherit this runtime and need the sandbox staged
            NodeRuntime.ensureSandboxTools(this)
            NodeRuntime.ensureToolWrappers(this)
            // pnpm must be spawnable or every `dsh plugin` op fails with EACCES
            NodeRuntime.ensurePnpmExecutable(this)
            mark("sandbox+wrappers+pnpm-heal")
            // must-read environment/update policy for agent sessions
            NodeRuntime.seedAgentInstructions(this)
            // storage access facts, measured from this process (the app's own
            // mount namespace — an adb shell session sees something different)
            try {
                StorageDiag.run(this)
            } catch (_: Exception) {
                // diagnostics must never keep the service from starting
            }
            mark("  seedAgentInstructions")
            try { StorageDiag.run(this) } catch (_: Exception) {}
            mark("  storageDiag")
            val needsInstall = Bootstrap.dshNeedsInstall(this)
            mark("  dshNeedsInstall=$needsInstall")
            mark("instructions+diag")
            if (needsInstall) {
                notify("正在安装 zcode / dsh 组件（首次需联网）…")
                val ok = Bootstrap.install(this) { line -> appendLog(line) }
                if (!ok) {
                    DshState.lastError = "组件安装失败，检查网络/镜像/代理后重试"
                    notify(DshState.lastError!!)
                    return
                }
            }
            mark("  re-stage: sandbox/wrappers/pnpm")
            // the package tree may have just been replaced: re-stage the shim
            // and rewrite the sandbox-mode file for the new version
            mark("re-stage after install")
            // A/B: a pending update becomes active for THIS boot; if the boot
            // fails below, revertFailedBoot swaps back and we retry once
            val verifying = AgentSlots.beginBoot(this, AgentSlots.DSH) { line -> appendLog(line) } > 0
            mark("  beginBoot verifying=$verifying")
            // an agent may have rewritten the tree from inside a session: adopt
            // the real version and re-apply the Android patches
            AgentSlots.reconcile(this, AgentSlots.DSH) { line -> appendLog(line) }
            mark("  reconcile done")
            mark("A/B boot + reconcile")
            // dsh uses the invoking directory as its workspace root
            val workDir = File(
                StorageAccess.defaultWorkspace(this),
            )
            val args = mutableListOf(
                NodeRuntime.nodeBin(this).absolutePath,
                // --expose-internals is required by dsh's HMR plugin
                "--expose-internals",
                NodeRuntime.dshEntryJs(this).absolutePath,
                "web",
                // --no-open: there is no desktop browser to spawn on Android
                "--no-open",
            )
            val pb = ProcessBuilder(args)
            if (workDir.isDirectory) pb.directory(workDir)
            // A previous dsh still holding 3080 makes the new one die with
            // EADDRINUSE, and then its required plugins never activate — which
            // reads as "plugin is broken" rather than "port is taken". Clean
            // stale listeners first (same /proc scan as the opencode service).
            mark("  killStaleWeb begin")
            killStaleWeb()
            mark("  killStaleWeb done")
            pb.environment().putAll(
                NodeRuntime.environment(this, mapOf("DEEPSEEK_API_KEY" to SecretStore.deepseekKey(this)))
            )
            pb.redirectErrorStream(true)
            val p = pb.start()
            mark("dsh process spawned")
            proc = p
            DshState.process = p
            thread(name = "dsh-log") {
                p.inputStream.bufferedReader().forEachLine { line ->
                    appendLog(line)
                    TOKEN_RE.find(line)?.let { DshState.startUrl = it.value }
                }
            }
            notify("等待 DeepSeek Harness 就绪…")
            // A verifying boot must also survive a version probe: a tree whose
            // entry file parses is not proof the release is usable (the first
            // A/B test with a faked version number booted fine and would have
            // been accepted). Cheap, and it fails the boot before the long wait.
            val versionOk = if (verifying) probeEntryVersion() else true
            // The port opens well before the web profile finishes booting; the
            // real readiness signal is the authenticated URL printed on stdout.
            val ready = versionOk && awaitTokenUrl(timeoutMs = 150_000)
            mark("token URL received (ready=$ready)")
            if (ready) {
                AgentSlots.markBootOk(this, AgentSlots.DSH)
                DshState.serverReady = true
                notify("DeepSeek Harness 运行中")
            } else {
                DshState.lastError = "dsh 未在 150 秒内就绪，详见 cache/dsh.log"
                notify(DshState.lastError!!)
                p.destroy()
                // A/B rollback: a verifying boot that fails reverts to the
                // previous tree and retries exactly once
                if (verifying && !retryAfterRevert &&
                    AgentSlots.revertFailedBoot(this, AgentSlots.DSH) { line -> appendLog(line) }
                ) {
                    DshState.lastError = null
                    boot(retryAfterRevert = true)
                }
            }
        } catch (e: Exception) {
            DshState.lastError = e.message ?: e.javaClass.simpleName
            notify("dsh 启动异常：${DshState.lastError}")
        }
    }

    /** Runs `<node> <entry> --version`; false when the candidate cannot start. */
    private fun probeEntryVersion(): Boolean = try {
        val pb = ProcessBuilder(
            NodeRuntime.nodeBin(this).absolutePath,
            NodeRuntime.dshEntryJs(this).absolutePath,
            "--version",
        )
        pb.environment().putAll(NodeRuntime.environment(this))
        pb.redirectErrorStream(true)
        val p = pb.start()
        p.inputStream.bufferedReader().forEachLine { appendLog("[verify] $it") }
        val ok = p.waitFor() == 0
        if (!ok) appendLog("[verify] 版本探测失败（退出码 ${p.exitValue()}），判定本次验证启动失败")
        ok
    } catch (e: Exception) {
        appendLog("[verify] 版本探测异常：${e.message}")
        false
    }

    private fun awaitTokenUrl(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (DshState.startUrl != null) return true
            if (proc?.isAlive == false) return false
            Thread.sleep(500)
        }
        return DshState.startUrl != null
    }

    private fun appendLog(line: String) {
        runCatching {
            val f = File(cacheDir, "dsh.log")
            if (f.length() > 512 * 1024) f.writeText("")
            f.appendText(line + "\n")
        }
    }

    override fun onDestroy() {
        proc?.destroy()
        DshState.serverReady = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Kills a leftover dsh web process. Without this the new one dies with
     * EADDRINUSE, its required plugins never activate, and the failure surfaces
     * as "2 required plugins did not activate" — which looks like a plugin bug
     * but is just the port being held. Only matches dsh's own argv.
     */
    private fun killStaleWeb() {
        val cmdline = "for p in /proc/[0-9]*; do " +
            "c=$(tr '\\000' ' ' < \$p/cmdline 2>/dev/null) || continue; " +
            "case \"\$c\" in *'dsh web'*|*'bin.js web'*) kill \${p#/proc/} 2>/dev/null;; esac; done"
        try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmdline)
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.use { it.readBytes() }
            p.waitFor()
        } catch (_: Exception) {
        }
        // Wait for the port to actually be released (bounded); a fixed sleep
        // both wastes time on a clean boot and can be too short after a kill.
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (!portBound(3080)) return
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun portBound(port: Int): Boolean = try {
        java.net.ServerSocket(port).use { false }
    } catch (_: java.net.BindException) {
        true // still held
    } catch (_: Exception) {
        false // anything else: assume free
    }
}
