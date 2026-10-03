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
