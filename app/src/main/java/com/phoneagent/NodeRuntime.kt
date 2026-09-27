package com.phoneagent

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Owns the embedded Node runtime: assets/runtime.tar.xz (staged in CI by
 * scripts/fetch-runtime.sh) is unpacked into filesDir/usr with modes and
 * symlinks preserved, and exposes the environment every Node process needs.
 *
 * Exec from filesDir is allowed because the app targets SDK 28 (see
 * app/build.gradle.kts).
 */
object NodeRuntime {

    fun usrDir(ctx: Context): File = File(ctx.filesDir, "usr")
    fun homeDir(ctx: Context): File = File(ctx.filesDir, "home").apply { mkdirs() }
    fun pkgDir(ctx: Context): File = File(ctx.filesDir, "pkg").apply { mkdirs() }

    private fun runtimeMarker(ctx: Context): File =
        File(ctx.filesDir, ".runtime-${Versions.RUNTIME}")

    fun isRuntimeExtracted(ctx: Context): Boolean =
        runtimeMarker(ctx).exists() && File(usrDir(ctx), "bin/node").let { it.exists() && it.canExecute() }

    fun nodeBin(ctx: Context): File = File(usrDir(ctx), "bin/node")
    fun npmCliJs(ctx: Context): File = File(usrDir(ctx), "lib/node_modules/npm/bin/npm-cli.js")
    fun zcodeEntryJs(ctx: Context): File =
        File(usrDir(ctx), "lib/node_modules/zcode-app-cli/bin/zcode.js")
    fun dshEntryJs(ctx: Context): File =
        File(usrDir(ctx), "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")

    fun isZcodeInstalled(ctx: Context): Boolean = zcodeEntryJs(ctx).exists()
    fun isDshInstalled(ctx: Context): Boolean = dshEntryJs(ctx).exists()

    /**
     * Blocking; call from a worker thread. Extracts assets/runtime.tar.xz into
     * filesDir, preserving unix modes and symlinks. Idempotent across versions:
     * each runtime version leaves its own marker file.
     */
    fun extractRuntime(ctx: Context) {
        val root = ctx.filesDir
        val rootPath = root.canonicalPath + File.separator
        ctx.assets.open("runtime.tar.xz").use { raw ->
            XZInputStream(raw).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    while (true) {
                        val entry: TarArchiveEntry = tar.nextEntry ?: break
                        val out = File(root, entry.name)
                        val canon = out.canonicalPath
                        if (canon != root.canonicalPath && !canon.startsWith(rootPath)) continue // zip-slip guard
                        when {
                            entry.isDirectory -> out.mkdirs()
                            entry.isSymbolicLink -> {
                                out.parentFile?.mkdirs()
                                out.delete()
                                try {
                                    Files.createSymbolicLink(out.toPath(), Paths.get(entry.linkName))
                                } catch (_: IOException) {
                                    // Filesystem refused the symlink; npm shims can tolerate it.
                                }
                            }
                            else -> {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { tar.copyTo(it, 1 shl 16) }
                                val ownerExec = entry.mode and 0b001000000 != 0
                                out.setExecutable(ownerExec, false)
                                out.setReadable(true, false)
                            }
                        }
                    }
                }
            }
        }
        runtimeMarker(ctx).writeText(Versions.RUNTIME)
    }

    /** Copies the bundled CLI tarballs from APK assets to a real path npm can read. */
    fun copyPackages(ctx: Context) {
        for (name in arrayOf(Versions.ZCODE_TGZ, Versions.DSH_TGZ)) {
            val out = File(pkgDir(ctx), name)
            ctx.assets.open("packages/$name").use { input ->
                FileOutputStream(out).use { input.copyTo(it, 1 shl 16) }
            }
        }
        // CI-built node-pty android-arm64 binding (optional until fetch-native.sh runs)
        try {
            ctx.assets.open("native/node-pty/pty.node").use { input ->
                val dir = File(pkgDir(ctx), "node-pty-prebuild").apply { mkdirs() }
                FileOutputStream(File(dir, "pty.node")).use { input.copyTo(it, 1 shl 16) }
            }
        } catch (_: IOException) {
            // asset absent: bootstrap continues without the dsh native binding
        }
    }

    fun environment(ctx: Context, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val usr = usrDir(ctx).absolutePath
        val env = mutableMapOf(
            "PATH" to "$usr/bin:/system/bin:/system/xbin:/vendor/bin",
            "LD_LIBRARY_PATH" to "$usr/lib",
            "HOME" to homeDir(ctx).absolutePath,
            "TMPDIR" to ctx.cacheDir.absolutePath,
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "NODE_PATH" to "$usr/lib/node_modules",
            "npm_config_registry" to Prefs.npmRegistry(ctx),
        )
        env.putAll(extra)
        // Node ignores Android's WiFi proxy settings; on proxy-only networks every
        // connect times out unless we push the proxy explicitly (NODE_USE_ENV_PROXY
        // makes Node 24+ undici fetch honor HTTPS_PROXY, npm honors it natively).
        val proxy = Prefs.httpProxy(ctx)
        if (proxy.isNotEmpty()) {
            env["HTTPS_PROXY"] = proxy
            env["HTTP_PROXY"] = proxy
            env["NO_PROXY"] = "localhost,127.0.0.1,::1"
            env["NODE_USE_ENV_PROXY"] = "1"
        }
        return env
    }
}
