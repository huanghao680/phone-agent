package com.phoneagent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Agent self-update: queries the npm registry for the latest version of each
 * bundled CLI and reinstalls from a freshly downloaded tarball when newer.
 *
 * The embedded CLIs have no self-update command (dsh delegates to plugins,
 * zcode has none), so updates mean a fresh `npm install -g` over the old tree.
 * Versions stay pinned in Versions.kt for a cold install; this path lets
 * users move without shipping a new APK.
 */
object AgentUpdate {

    data class Info(val name: String, val installed: String?, val latest: String) {
        val updateAvailable: Boolean get() = installed != null && installed != latest
    }

    private fun registry(ctx: Context): String = Prefs.npmRegistry(ctx).trimEnd('/')

    /** Latest version of [pkg] from the configured registry (falls back to npmjs). */
    fun latestVersion(ctx: Context, pkg: String): String? = try {
        val encoded = pkg.replace("/", "%2f")
        val url = URL("${registry(ctx)}/$encoded/latest")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/json")
        conn.inputStream.bufferedReader().use { it.readText() }.let { JSONText -> JSONObject(JSONText).getString("version") }
    } catch (_: Exception) {
        null
    }

    fun installedVersions(ctx: Context): Triple<String?, String?, String?> {
        val usr = NodeRuntime.usrDir(ctx)
        fun ver(vararg path: String): String? = try {
            JSONObject(File(usr, *path).readText()).getString("version")
        } catch (_: Exception) {
            null
        }
        val zcode = ver("lib/node_modules/zcode-app-cli/package.json")
        val dsh = ver("lib/node_modules/@deepseek-ai/dsh/package.json")
        return Triple(zcode, dsh, null)
    }

    fun status(ctx: Context): List<Info> {
        val (zcode, dsh, _) = installedVersions(ctx)
        val reg = registry(ctx)
        return listOf(
            Info("zcode-app-cli", zcode, latestVersion(ctx, "zcode-app-cli") ?: "未知"),
            Info("@deepseek-ai/dsh", dsh, latestVersion(ctx, "@deepseek-ai/dsh") ?: "未知"),
        ).map {
            // registry may be a mirror; if the query failed show "未知" rather than blocking
            if (it.latest == "未知" && reg != "https://registry.npmjs.org") it else it
        }
    }

    /** Downloads [pkg]@[version] tarball into the pkg dir and returns the file. */
    fun fetchTarball(ctx: Context, pkg: String, version: String, onLine: (String) -> Unit): File? {
        val enc = pkg.replace("/", "%2f")
        val meta = try {
            val conn = URL("${registry(ctx)}/$enc").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            onLine("[update] 无法获取 $pkg 元数据：${e.message}")
            return null
        }
        val tarball = try {
            meta.getJSONObject("versions").getJSONObject(version).getString("dist").let { it.getString("tarball") }
        } catch (_: Exception) {
            onLine("[update] registry 上没有 $pkg@$version")
            return null
        }
        val safe = pkg.replace("@", "").replace("/", "-")
        val out = File(NodeRuntime.pkgDir(ctx), "$safe-$version.tgz")
        onLine("[update] 下载 $pkg@$version ...")
        try {
            val conn = URL(tarball).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
        } catch (e: Exception) {
            onLine("[update] 下载失败：${e.message}")
            out.delete()
            return null
        }
        onLine("[update] 下载完成：${"%.1f".format(out.length() / 1048576.0)}MB")
        return out
    }

    /**
     * Reinstalls [tgzFile] over the installed tree (npm keeps the old files
     * until the new install completes, so a failed update leaves the old CLI
     * usable). Clears the version marker so the bootstrap re-injects pty/sharp.
     */
    fun applyUpdate(
        ctx: Context,
        pkg: String,
        tgzFile: File,
        onLine: (String) -> Unit,
    ): Boolean {
        val usr = NodeRuntime.usrDir(ctx)
        val node = NodeRuntime.nodeBin(ctx)
        val npm = NodeRuntime.npmCliJs(ctx)
        val ignoreScripts = pkg != "zcode-app-cli"
        val args = mutableListOf(
            node.absolutePath, npm.absolutePath,
            "install", "-g", "--prefix", usr.absolutePath,
        )
        if (ignoreScripts) args.add("--ignore-scripts")
        args.add("file:${tgzFile.absolutePath}")
        val pb = ProcessBuilder(args)
        pb.environment().putAll(NodeRuntime.environment(ctx))
        pb.redirectErrorStream(true)
        onLine("[update] 安装 $pkg ...")
        return try {
            val p = pb.start()
            p.inputStream.bufferedReader().forEachLine(onLine)
            val code = p.waitFor()
            if (code == 0) {
                markerFor(ctx, pkg).takeIf { it.exists() }?.delete()
                // re-inject android-specific pieces after a scripted install
                if (pkg == "@deepseek-ai/dsh") {
                    injectPty(ctx, onLine)
                    sharpWasm(ctx, node, npm, usr, onLine)
                }
                onLine("[update] $pkg 更新完成 ✅")
                true
            } else {
                onLine("[update] $pkg 安装失败（退出码 $code），旧版本仍可用")
                false
            }
        } catch (e: Exception) {
            onLine("[update] $pkg 更新异常：${e.message}")
            false
        }
    }

    private fun markerFor(ctx: Context, pkg: String): File = File(
        NodeRuntime.usrDir(ctx),
        if (pkg == "zcode-app-cli") ".cli-installed-zcode" else ".cli-installed-dsh",
    )

    private fun injectPty(ctx: Context, onLine: (String) -> Unit) {
        val src = File(NodeRuntime.pkgDir(ctx), "node-pty-prebuild/pty.node")
        val dir = File(
            NodeRuntime.usrDir(ctx),
            "lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty/prebuilds/android-arm64",
        )
        if (src.exists()) {
            dir.mkdirs()
            src.copyTo(File(dir, "pty.node"), overwrite = true)
            onLine("[update] 已重新注入 node-pty 安卓预编译。")
        }
    }

    private fun sharpWasm(ctx: Context, node: File, npm: File, usr: File, onLine: (String) -> Unit) {
        try {
            val sharpDir = File(usr, "lib/node_modules/@deepseek-ai/dsh/node_modules/sharp")
            val sharpPkg = File(sharpDir, "package.json")
            if (!sharpPkg.exists()) return
            val ver = org.json.JSONObject(sharpPkg.readText()).getString("version")
            if (File(sharpDir, "node_modules/@img/sharp-wasm32/package.json").exists()) return
            onLine("[update] 安装 @img/sharp-wasm32@$ver ...")
            val pb = ProcessBuilder(
                node.absolutePath, npm.absolutePath,
                "install", "--prefix", sharpDir.absolutePath,
                "--no-save", "--no-package-lock", "--ignore-scripts",
                "@img/sharp-wasm32@$ver",
            )
            pb.environment().putAll(NodeRuntime.environment(ctx))
            pb.directory(sharpDir)
            pb.redirectErrorStream(true)
            pb.start().waitFor()
        } catch (e: Exception) {
            onLine("[update] sharp-wasm32 安装失败（图片功能受限）：${e.message}")
        }
    }
}
