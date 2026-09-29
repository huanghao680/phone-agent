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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notifications.ID_DSH, Notifications.build(this, "正在启动 DeepSeek Harness…"))
        DshState.serverReady = false
        DshState.lastError = null
        thread(name = "dsh-boot") { boot() }
        return START_STICKY
    }

    private fun notify(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        mgr.notify(Notifications.ID_DSH, Notifications.build(this, text))
    }

    private fun boot() {
        try {
            if (!NodeRuntime.isRuntimeExtracted(this)) {
                notify("正在解压 Node 运行时…")
                NodeRuntime.extractRuntime(this)
            }
            // bwrap shim + sandbox-mode file: the web GUI itself runs
            // danger-full-access, but headless/CLI profiles spawned from
            // sessions inherit this runtime and need the sandbox staged
            NodeRuntime.ensureSandboxTools(this)
            if (Bootstrap.dshNeedsInstall(this)) {
                notify("正在安装 zcode / dsh 组件（首次需联网）…")
                val ok = Bootstrap.install(this) { line -> appendLog(line) }
                if (!ok) {
                    DshState.lastError = "组件安装失败，检查网络/镜像/代理后重试"
                    notify(DshState.lastError!!)
                    return
                }
            }
            // the package tree may have just been replaced: re-stage the shim
            // and rewrite the sandbox-mode file for the new version
            NodeRuntime.ensureSandboxTools(this)
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
            pb.environment().putAll(
                NodeRuntime.environment(this, mapOf("DEEPSEEK_API_KEY" to SecretStore.deepseekKey(this)))
            )
            pb.redirectErrorStream(true)
            val p = pb.start()
            proc = p
            thread(name = "dsh-log") {
                p.inputStream.bufferedReader().forEachLine { line ->
                    appendLog(line)
                    TOKEN_RE.find(line)?.let { DshState.startUrl = it.value }
                }
            }
            notify("等待 DeepSeek Harness 就绪…")
            // The port opens well before the web profile finishes booting; the
            // real readiness signal is the authenticated URL printed on stdout.
            val ready = awaitTokenUrl(timeoutMs = 150_000)
            if (ready) {
                DshState.serverReady = true
                notify("DeepSeek Harness 运行中")
            } else {
                DshState.lastError = "dsh 未在 150 秒内就绪，详见 cache/dsh.log"
                notify(DshState.lastError!!)
                p.destroy()
            }
        } catch (e: Exception) {
            DshState.lastError = e.message ?: e.javaClass.simpleName
            notify("dsh 启动异常：${DshState.lastError}")
        }
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
}
