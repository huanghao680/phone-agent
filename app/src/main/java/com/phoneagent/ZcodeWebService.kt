package com.phoneagent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.io.File
import kotlin.concurrent.thread

/** Shared state between ZcodeWebService and ZcodeWebActivity. */
object ZcodeWebState {
    @Volatile var ready = false
    @Volatile var lastError: String? = null
    @Volatile var port: Int = 3030
}

/**
 * Foreground service running the official ZCode HTTP server (@zcode/server) on
 * the embedded Node, serving the bundled web SPA over 127.0.0.1:3030.
 */
class ZcodeWebService : Service() {

    private var proc: Process? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notifications.ID_ZCODE_WEB, Notifications.build(this, "正在启动 Zcode Web…"))
        ZcodeWebState.ready = false
        ZcodeWebState.lastError = null
        thread(name = "zcode-web-boot") { boot() }
        return START_STICKY
    }

    private fun notify(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        mgr.notify(Notifications.ID_ZCODE_WEB, Notifications.build(this, text))
    }

    private fun boot() {
        try {
            if (!NodeRuntime.isRuntimeExtracted(this)) {
                notify("正在解压 Node 运行时…")
                NodeRuntime.extractRuntime(this)
            }
            // the web server drives the zcode CLI as its agent backend, so the
            // CLI must be present at the packaged version
            if (Bootstrap.zcodeNeedsInstall(this)) {
                notify("正在安装 zcode 组件（首次需联网）…")
                Bootstrap.install(this) { line -> appendLog(line) }
            }
            extractBundles()
            val srvDir = File(filesDir, "zcode-server")
            val entry = File(srvDir, "dist/entry-http.js")
            if (!entry.exists()) {
                ZcodeWebState.lastError = "Zcode Web 服务端缺失（当前构建未包含）"
                notify(ZcodeWebState.lastError!!)
                return
            }
            val workDir = File(StorageAccess.defaultWorkspace(this))
            val pb = ProcessBuilder(NodeRuntime.nodeBin(this).absolutePath, entry.absolutePath)
            if (workDir.isDirectory) pb.directory(workDir)
            pb.environment().putAll(
                NodeRuntime.environment(
                    this,
                    mapOf(
                        // the server serves the SPA from this root once set
                        "ZCODE_WEB_STATIC_ROOT" to File(filesDir, "zcode-web").absolutePath,
                        "ZCODE_HOME" to NodeRuntime.homeDir(this).absolutePath,
                        "ZCODE_AGENT_SERVER_COMMAND" to NodeRuntime.zcodeEntryJs(this).absolutePath,
                        "ZCODE_AGENT_SERVER_CWD" to
                            (StorageAccess.defaultWorkspace(this)),
                    ),
                )
            )
            pb.redirectErrorStream(true)
            val p = pb.start()
            proc = p
            thread(name = "zcode-web-log") {
                p.inputStream.bufferedReader().forEachLine { appendLog(it) }
            }
            if (waitReady(150_000)) {
                ZcodeWebState.ready = true
                notify("Zcode Web 运行中：127.0.0.1:${ZcodeWebState.port}")
            } else {
                ZcodeWebState.lastError = "Zcode Web 未在 150 秒内就绪，详见 cache/zcode-web.log"
                notify(ZcodeWebState.lastError!!)
                p.destroy()
            }
        } catch (e: Exception) {
            ZcodeWebState.lastError = e.message ?: e.javaClass.simpleName
            notify("Zcode Web 启动异常：${ZcodeWebState.lastError}")
        }
    }

    /** Copies the bundled server runtime + SPA out of APK assets on first run. */
    private fun extractBundles() {
        val marker = File(filesDir, ".zcode-web-${Versions.ZCODE_WEB}")
        val serverEntry = File(filesDir, "zcode-server/dist/entry-http.js")
        // self-heal: a stale marker from a failed/older extraction must not
        // block re-extraction when the server entry is missing
        if (marker.exists() && serverEntry.exists()) return
        notify("正在展开 Zcode Web 运行时…")
        // Server bundle ships as a single tarball to keep thousands of small
        // npm files out of the APK's asset index.
        val tgz = File(filesDir, "zcode-server.tgz")
        try {
            assets.open("zcode-server/zcode-server.tgz").use { input ->
                tgz.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            ProcessBuilder("/system/bin/sh", "-c", "tar -xzf ${tgz.absolutePath} -C ${filesDir.absolutePath}")
                .start().waitFor()
            tgz.delete()
        } catch (_: Exception) {
            // assets absent (build without the web bundle)
        }
        copyAssetDir("zcode-web", File(filesDir, "zcode-web"))
        marker.writeText(Versions.ZCODE_WEB)
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val list = assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            assets.open(assetPath).use { input ->
                dest.parentFile?.mkdirs()
                dest.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            return
        }
        dest.mkdirs()
        for (name in list) copyAssetDir("$assetPath/$name", File(dest, name))
    }

    private fun waitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (proc?.isAlive == false) return false
            try {
                java.net.Socket("127.0.0.1", ZcodeWebState.port).use {
                    // the server prints its URL before routes are live; give it
                    // a moment, then accept an HTTP response of any kind
                    Thread.sleep(1500)
                    return true
                }
            } catch (_: Exception) {
                Thread.sleep(1000)
            }
        }
        return false
    }

    private fun appendLog(line: String) {
        runCatching {
            val f = File(cacheDir, "zcode-web.log")
            if (f.length() > 512 * 1024) f.writeText("")
            f.appendText(line + "\n")
        }
    }

    override fun onDestroy() {
        proc?.destroy()
        ZcodeWebState.ready = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
