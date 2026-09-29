package com.phoneagent

import android.content.Context
import java.io.File

/**
 * Storage access probe, run inside the app process.
 *
 * Storage isolation on Android 11+ is per-mount-namespace, so the same checks
 * made from an `adb shell run-as` session answer a different question than the
 * app's own: only a process spawned by the app (the app itself, or an agent it
 * launches) sees the FUSE view the app is actually granted. This writes the
 * app's own verdict to a file so it can be inspected from outside.
 *
 * Reported per path: existence, readability, writability (real create+delete)
 * and whether a copied binary can be executed from it (shared storage is a
 * noexec FUSE mount, so a workspace there cannot host the agents' binaries).
 */
object StorageDiag {

    fun reportPath(ctx: Context): File = File(ctx.cacheDir, "storage-diag.txt")

    fun run(ctx: Context) {
        val sb = StringBuilder()
        sb.append("phone-agent storage diagnostics\n")
        sb.append("package: ${ctx.packageName}\n")
        sb.append("sdk: ${android.os.Build.VERSION.SDK_INT}\n")
        sb.append("allFiles: ${StorageAccess.isAllFilesGranted(ctx)}\n\n")

        val examples = listOf(
            "internal files" to ctx.filesDir,
            "internal cache" to ctx.cacheDir,
            "external app root" to File("/storage/emulated/0/Android/data/${ctx.packageName}"),
            "external files" to (ctx.getExternalFilesDir(null) ?: File("/nonexistent")),
            "shared Download" to File("/storage/emulated/0/Download"),
            "shared root" to File("/storage/emulated/0"),
            "app home (agent workspace)" to NodeRuntime.homeDir(ctx),
        )
        for ((label, dir) in examples) {
            sb.append(label).append("  ").append(dir.absolutePath).append('\n')
            sb.append("  exists=").append(dir.exists())
                .append(" dir=").append(dir.isDirectory)
                .append(" canRead=").append(dir.canRead())
                .append(" canWriteFlag=").append(dir.canWrite())
            sb.append("  write=").append(probeWrite(dir))
            sb.append("  exec=").append(probeExec(dir))
            sb.append('\n')
        }

        // what the agents see: getExternalFilesDir is the documented app-private
        // directory on shared storage; Android/data/<pkg> is its parent
        sb.append("\nexternalFilesDir=").append(ctx.getExternalFilesDir(null)?.absolutePath ?: "null")
        sb.append('\n')

        reportPath(ctx).writeText(sb.toString())
    }

    private fun probeWrite(dir: File): String {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return "no-dir"
            val f = File(dir, ".phone-agent-write-probe")
            f.writeText("probe")
            val ok = f.readText() == "probe"
            f.delete()
            if (ok) "ok" else "mismatch"
        } catch (e: Exception) {
            "fail:${e.javaClass.simpleName}"
        }
    }

    private fun probeExec(dir: File): String {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return "no-dir"
            val src = File("/system/bin/toybox")
            if (!src.exists()) return "no-source"
            val bin = File(dir, ".phone-agent-exec-probe")
            src.inputStream().use { input -> bin.outputStream().use { input.copyTo(it) } }
            bin.setExecutable(true, false)
            val p = ProcessBuilder(bin.absolutePath, "true").redirectErrorStream(true).start()
            val code = p.waitFor()
            bin.delete()
            "ok(code=$code)"
        } catch (e: Exception) {
            "fail:${e.javaClass.simpleName}"
        }
    }
}
