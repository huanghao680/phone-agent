package com.phoneagent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
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
 */
class OpencodeWebService : Service() {

    private var proc: Process? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notifications.ID_OPENCODE, Notifications.build(this, "正在启动 opencode…"))
        OpencodeState.serverReady = false
        OpencodeState.lastError = null
        thread(name = "opencode-boot") { boot() }
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
            NodeRuntime.copyPackages(this)
            NodeRuntime.ensureSandboxTools(this)
            NodeRuntime.seedAgentInstructions(this)
            if (!BinaryAgents.isReady(this, BinaryAgents.OPENCODE)) {
                notify("正在解压 opencode…")
                if (!BinaryAgents.ensure(this, BinaryAgents.OPENCODE) { line -> appendLog(line) }) {
                    OpencodeState.lastError = "opencode 解压失败（当前构建未包含）"
                    notify(OpencodeState.lastError!!)
                    return
                }
            }
            val workDir = File(StorageAccess.defaultWorkspace(this))

            val args = mutableListOf(
                File(pkgLDLoader(this)).absolutePath,
                File(NodeRuntime.pkgDir(this), "opencode").absolutePath,
                "serve",
                "--port", "4096",
                "--hostname", "127.0.0.1",
                "--print-logs",
            )
            val pb = ProcessBuilder(args)
            if (workDir.isDirectory) pb.directory(workDir)
            val env = NodeRuntime.environment(this).toMutableMap()
            // Bun needs a writable temp dir (no /tmp on Android) and the GNU C++
            // runtime next to the musl loader
            env["TMPDIR"] = cacheDir.absolutePath
            env["LD_LIBRARY_PATH"] = NodeRuntime.pkgDir(this).absolutePath
            // Bun's resolver cannot read Android's DNS config; route its
            // network through the local Node forward proxy
            val (proxyUrl, _) = NodeRuntime.ensureDnsProxy(this)
            env["HTTP_PROXY"] = proxyUrl
            env["HTTPS_PROXY"] = proxyUrl
            env["NO_PROXY"] = "127.0.0.1,localhost"
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            // serve writes to the redirected file; appendLog kept for parity
            val serveLog = File(cacheDir, "opencode-serve.log")
            pb.redirectOutput(serveLog)
            val p = pb.start()
            proc = p
            thread(name = "opencode-log") {
                p.inputStream.bufferedReader().forEachLine { line -> appendLog(line) }
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
                OpencodeState.lastError = "opencode 未在 60 秒内就绪" + (tail?.let { "：$it" } ?: "，详见 cache/opencode-serve.log")
                notify(OpencodeState.lastError!!)
                p.destroy()
            }
        } catch (e: Exception) {
            OpencodeState.lastError = e.message ?: e.javaClass.simpleName
            notify("opencode 启动异常：${OpencodeState.lastError}")
        }
    }

    private fun pkgLDLoader(ctx: Context): String = File(NodeRuntime.pkgDir(ctx), "ld-musl-aarch64.so.1").absolutePath

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
        proc?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OpencodeWebService::class.java))
        }
    }
}
