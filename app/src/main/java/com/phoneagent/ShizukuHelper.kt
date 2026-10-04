package com.phoneagent

import android.app.Activity
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.io.InputStream
import java.io.OutputStream

/**
 * Thin wrapper over the Shizuku client API.
 *
 * Shizuku exposes an adb-level (shell uid 2000) binder to normal apps: the
 * user installs the Shizuku manager and starts its server once per boot
 * (wireless debugging on Android 11+, or adb/root). Processes created here
 * run in the shell context — crucially WITHOUT the seccomp filter zygote
 * installs for app processes, which is what kills Bun (opencode) when the
 * app spawns it directly. Wrapping with `run-as com.phoneagent` re-enters
 * the app's private data (debug builds only — run-as needs a debuggable app).
 *
 * Shizuku.newProcess is private in API 13, so we talk to IShizukuService
 * through a ShizukuBinderWrapper and adapt the returned IRemoteProcess to
 * java.lang.Process ourselves.
 */
object ShizukuHelper {

    const val REQUEST_CODE = 4271

    fun available(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun granted(): Boolean = try {
        available() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun request(activity: Activity) {
        try {
            if (!available()) return
            if (Shizuku.isPreV11()) return
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {
        }
    }

    /** Runs [cmd] via the Shizuku server (shell uid). [dir] may be null. */
    fun newProcess(cmd: List<String>, dir: String?): Process {
        val svc = IShizukuService.Stub.asInterface(
            ShizukuBinderWrapper(Shizuku.getBinder()!!),
        )
        return RemoteProcessAdapter(svc.newProcess(cmd.toTypedArray(), null, dir))
    }

    /** Convenience: `sh -c [command]` in the shell context. */
    fun sh(command: String, dir: String? = null): Process =
        newProcess(listOf("sh", "-c", command), dir)

    /**
     * Ports in LISTEN state, read from /proc/net/tcp. This is the shell's
     * view: Android 11+ hides /proc/net from app processes, so the app cannot
     * see its own listeners otherwise (it can only blind-probe them).
     */
    fun listeningPorts(): List<Int> {
        if (!granted()) return emptyList()
        val out = ArrayList<Int>()
        try {
            val p = sh("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null")
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            for (line in text.lineSequence().drop(1)) {
                // sl local_address rem_address st ...; 0A = TCP_LISTEN
                val cols = line.trim().split(WHITESPACE)
                if (cols.size <= 3) continue
                if (cols[3] != "0A") continue
                val port = cols[1].substringAfter(':').toIntOrNull(16) ?: continue
                if (port !in out) out.add(port)
            }
        } catch (_: Exception) {
            return emptyList()
        }
        out.sort()
        return out
    }

    /** Human-readable listening-port table, one line per port. */
    fun portReport(): String {
        val ports = listeningPorts()
        if (ports.isEmpty()) return "未读取到监听端口（Shizuku 未授权或 /proc/net 不可读）"
        return ports.joinToString("  ") { "$it${PORT_HINTS[it]?.let { h -> " [$h]" } ?: ""}" }
    }

    /**
     * Grants "all files access" (MANAGE_EXTERNAL_STORAGE) through Shizuku.
     * Normally the user has to dig into a special settings page for this; the
     * shell uid holds the appops permission itself, so one command does it.
     */
    fun grantAllFilesAccess(): List<String> {
        if (!granted()) return listOf("Shizuku 未授权或未运行")
        val out = mutableListOf<String>()
        val before = appOpsLine()
        val p = sh("appops set --uid $APP_UID MANAGE_EXTERNAL_STORAGE allow 2>&1")
        val text = p.inputStream.bufferedReader().use { it.readText() }
        val rc = p.waitFor()
        val after = appOpsLine()
        out += if (rc == 0) "[ok] appops set --uid $APP_UID MANAGE_EXTERNAL_STORAGE allow"
        else "[fail] ($rc) $text"
        out += "授权前: $before"
        out += "授权后: $after"
        return out
    }

    /**
     * Adds this app to the device-idle (Doze) whitelist. Without it the
     * system throttles our foreground services and the agent sessions they
     * host get killed in the background; shell can whitelist, an app cannot.
     */
    fun whitelistFromBatteryOptimization(): List<String> {
        if (!granted()) return listOf("Shizuku 未授权或未运行")
        val out = mutableListOf<String>()
        val p = sh("dumpsys deviceidle whitelist +$PKG 2>&1")
        val text = p.inputStream.bufferedReader().use { it.readText() }
        val rc = p.waitFor()
        out += if (rc == 0) "[ok] 已加入电池优化白名单" else "[fail] ($rc) $text"
        val check = sh("dumpsys deviceidle whitelist 2>/dev/null | grep -i $PKG")
        val present = check.inputStream.bufferedReader().use { it.readText() }
        check.waitFor()
        out += "白名单状态: " + (present.trim().ifEmpty { "（未生效）" })
        return out
    }

    /**
     * Grants POST_NOTIFICATIONS. Our services report through ongoing
     * notifications, so losing this (denied at install on Android 13+)
     * hides failures like a stalled webui from the user entirely.
     */
    fun grantNotifications(): List<String> {
        if (!granted()) return listOf("Shizuku 未授权或未运行")
        val p = sh("pm grant $PKG android.permission.POST_NOTIFICATIONS 2>&1")
        val text = p.inputStream.bufferedReader().use { it.readText() }
        val rc = p.waitFor()
        return listOf(if (rc == 0 || text.isBlank()) "[ok] 已授予通知权限" else "[fail] ($rc) $text")
    }

    /** Reads the current MANAGE_EXTERNAL_STORAGE appop state for our uid. */
    private fun appOpsLine(): String = try {
        val p = sh("appops get --uid $APP_UID MANAGE_EXTERNAL_STORAGE 2>&1")
        val t = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        t.trim().lineSequence().lastOrNull()?.trim().orEmpty().ifEmpty { "(none)" }
    } catch (e: Exception) {
        "(appops 不可用: ${e.message})"
    }

    /**
     * Fixes the phantom-process limit through Shizuku (shell has
     * WRITE_SECURE_SETTINGS, root not needed). Returns human-readable lines.
     */
    fun fixPhantomProcesses(): List<String> {
        val out = mutableListOf<String>()
        if (!granted()) return listOf("Shizuku 未授权或未运行")
        for (c in arrayOf(
            "device_config put activity_manager max_phantom_processes 2147483647",
            "settings put global settings_enable_monitor_phantom_procs false",
        )) {
            val r = try {
                val p = sh(c)
                val text = p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()
                if (p.exitValue() == 0) "[ok] $c" else "[fail] $c :: $text"
            } catch (e: Exception) {
                "[fail] $c :: ${e.message}"
            }
            out += r
        }
        out += "完成（Shizuku）。该设置在系统 OTA / 重启后可能需要重新执行。"
        return out
    }

    /** Our own uid, as appops expects it. Resolved lazily from the process. */
    private val APP_UID: Int get() = android.os.Process.myUid()

    private val WHITESPACE = Regex("[ \t]+")

    private const val PKG = "com.phoneagent"

    /** Ports this app uses, so the report is readable rather than raw. */
    private val PORT_HINTS: Map<Int, String> = mapOf(
        3030 to "zcode web",
        3080 to "dsh web",
        4096 to "opencode webui",
        8118 to "DNS proxy",
    )
}

/** Adapts Shizuku's IRemoteProcess binder interface to java.lang.Process. */
private class RemoteProcessAdapter(private val remote: moe.shizuku.server.IRemoteProcess) : Process() {

    private val exited = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var code: Int? = null

    override fun getOutputStream(): OutputStream =
        ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream)

    override fun getInputStream(): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)

    override fun getErrorStream(): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)

    override fun waitFor(): Int {
        if (code == null) {
            code = try {
                remote.waitFor()
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                throw RuntimeException("shizuku waitFor failed: ${e.message}", e)
            }
            exited.set(true)
        }
        return code!!
    }

    override fun exitValue(): Int {
        if (code != null) return code!!
        try {
            code = remote.exitValue()
            exited.set(true)
            return code!!
        } catch (_: Throwable) {
            throw IllegalThreadStateException("process has not exited")
        }
    }

    override fun destroy() {
        try {
            remote.destroy()
        } catch (_: Throwable) {
        }
    }
}
