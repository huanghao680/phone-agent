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
}

/**
 * Foreground service hosting the DeepSeek Harness web server (embedded Node,
 * 127.0.0.1:3080). First run also performs runtime extraction and CLI install.
 */
class DshService : Service() {

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
            if (!Bootstrap.isDshInstalled(this)) {
                notify("正在安装 zcode / dsh 组件（首次需联网）…")
                val ok = Bootstrap.install(this) { line -> appendLog(line) }
                if (!ok) {
                    DshState.lastError = "组件安装失败，检查网络/镜像/代理后重试"
                    notify(DshState.lastError!!)
                    return
                }
            }
            val pb = ProcessBuilder(
                NodeRuntime.nodeBin(this).absolutePath,
                NodeRuntime.dshEntryJs(this).absolutePath,
                "web",
            )
            pb.environment().putAll(
                NodeRuntime.environment(this, mapOf("DEEPSEEK_API_KEY" to Prefs.deepseekKey(this)))
            )
            pb.redirectErrorStream(true)
            val p = pb.start()
            proc = p
            thread(name = "dsh-log") {
                p.inputStream.bufferedReader().forEachLine { appendLog(it) }
            }
            notify("等待 DeepSeek Harness 就绪…")
            val ready = waitPort(3080, timeoutMs = 90_000)
            if (ready) {
                DshState.serverReady = true
                notify("DeepSeek Harness 运行中：127.0.0.1:3080")
            } else {
                DshState.lastError = "dsh 服务 90 秒内未就绪，详见 cache/dsh.log"
                notify(DshState.lastError!!)
                p.destroy()
            }
        } catch (e: Exception) {
            DshState.lastError = e.message ?: e.javaClass.simpleName
            notify("dsh 启动异常：${DshState.lastError}")
        }
    }

    private fun waitPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (proc == null) return false
            try {
                java.net.Socket("127.0.0.1", port).use { return true }
            } catch (_: Exception) {
                Thread.sleep(500)
            }
        }
        return false
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
