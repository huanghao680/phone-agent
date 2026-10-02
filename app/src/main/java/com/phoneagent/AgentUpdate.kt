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

    /**
     * Opens a registry connection, honouring the configured HTTP proxy.
     * HttpURLConnection ignores the proxy Prefs on its own, so on proxy-only
     * networks every request timed out ("无法获取元数据：timeout").
     */
    private fun open(ctx: Context, url: String): HttpURLConnection {
        val proxy = Prefs.httpProxy(ctx)
        if (proxy.isNotEmpty()) {
            runCatching {
                val host = proxy.substringAfter("://").substringBefore(':')
                val port = proxy.substringAfterLast(':').trimEnd('/').toIntOrNull() ?: 80
                val p = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port))
                URL(url).openConnection(p) as HttpURLConnection
            }.onSuccess { return it }
        }
        return URL(url).openConnection() as HttpURLConnection
    }

    /**
     * Registry JSON via the embedded Node.
     *
     * The Java stack still fails on https-over-CONNECT through some proxies
     * (verified on device: HttpURLConnection timed out on 4 of 5 package URLs
     * while Node's fetch returned all five), so this is the primary path for
     * metadata. Node needs HTTPS_PROXY/NODE_USE_ENV_PROXY to be set, which
     * NodeRuntime.environment() already does.
     */
    private fun nodeFetchJson(ctx: Context, url: String): JSONObject? {
        val node = NodeRuntime.nodeBin(ctx)
        if (!node.canExecute()) return null
        val script = "fetch(process.argv[1],{signal:AbortSignal.timeout(20000)})" +
            ".then(r=>{if(!r.ok)throw new Error('HTTP '+r.status);return r.text()})" +
            ".then(t=>{process.stdout.write(t);process.exit(0)})" +
            ".catch(e=>{process.stderr.write(String(e.message));process.exit(1)})"
        return try {
            val pb = ProcessBuilder(node.absolutePath, "-e", script, url)
            pb.environment().putAll(NodeRuntime.environment(ctx))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0 || out.isBlank()) null else runCatching { JSONObject(out) }.getOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** Registry JSON for [url]: Node first (proxy-aware), Java as fallback. */
    fun registryJsonFor(ctx: Context, url: String): JSONObject? =
        nodeFetchJson(ctx, url) ?: runCatching {
            val conn = open(ctx, url)
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        }.getOrNull()

    /**
     * Downloads [url] to [out], again Node-first: tarballs are hundreds of MB
     * and the Java path times out through the proxy where Node's fetch works.
     */
    private fun download(ctx: Context, url: String, out: File, onFail: (String) -> Unit): Boolean {
        nodeDownload(ctx, url, out)?.let { return it }
        return try {
            val conn = open(ctx, url)
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
            true
        } catch (e: Exception) {
            onFail(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** Fetch [url] to [out] with the embedded Node. Null when Node is unusable. */
    private fun nodeDownload(ctx: Context, url: String, out: File): Boolean? {
        val node = NodeRuntime.nodeBin(ctx)
        if (!node.canExecute()) return null
        val tmp = File(out.absolutePath + ".part")
        val script = "const{createWriteStream}=require('fs');" +
            "fetch(process.argv[1]).then(r=>{if(!r.ok)throw new Error('HTTP '+r.status);" +
            "const w=createWriteStream(process.argv[2]);" +
            "return new Promise((res,rej)=>{r.body.pipe(w);w.on('finish',res);w.on('error',rej)})})" +
            ".then(()=>process.exit(0)).catch(e=>{process.stderr.write(String(e.message));process.exit(1)})"
        return try {
            val pb = ProcessBuilder(node.absolutePath, "-e", script, url, tmp.absolutePath)
            pb.environment().putAll(NodeRuntime.environment(ctx))
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.bufferedReader().readText()
            val rc = p.waitFor()
            if (rc == 0 && tmp.exists() && tmp.length() > 0) {
                tmp.renameTo(out)
                true
            } else {
                tmp.delete()
                false // Node ran and failed: report it rather than retrying Java
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Latest version of [pkg] from the configured registry (falls back to npmjs). */
    fun latestVersion(ctx: Context, pkg: String): String? = try {
        val encoded = pkg.replace("/", "%2f")
        registryJsonFor(ctx, "${registry(ctx)}/$encoded/latest")?.getString("version")
    } catch (_: Exception) {
        null
    }

    fun installedVersions(ctx: Context): Pair<String?, String?> {
        val usr = NodeRuntime.usrDir(ctx)
        fun ver(pkgPath: String): String? = try {
            JSONObject(File(usr, pkgPath).readText()).getString("version")
        } catch (_: Exception) {
            null
        }
        val zcode = ver("lib/node_modules/zcode-app-cli/package.json")
        val dsh = ver("lib/node_modules/@deepseek-ai/dsh/package.json")
        return Pair(zcode, dsh)
    }

    fun status(ctx: Context): List<Info> {
        val (zcode, dsh) = installedVersions(ctx)
        val reg = registry(ctx)
        return listOf(
            Info("zcode-app-cli", zcode, latestVersion(ctx, "zcode-app-cli") ?: "未知"),
            Info("@deepseek-ai/dsh", dsh, latestVersion(ctx, "@deepseek-ai/dsh") ?: "未知"),
            // standalone binary agents (terminal picker)
            Info("codex", BinaryAgents.installedVersion(ctx, BinaryAgents.CODEX), BinaryAgents.latestVersion(ctx, BinaryAgents.CODEX) ?: "未知"),
            Info("claude", BinaryAgents.installedVersion(ctx, BinaryAgents.CLAUDE), BinaryAgents.latestVersion(ctx, BinaryAgents.CLAUDE) ?: "未知"),
            Info("opencode", BinaryAgents.installedVersion(ctx, BinaryAgents.OPENCODE), BinaryAgents.latestVersion(ctx, BinaryAgents.OPENCODE) ?: "未知"),
        )
    }

    /** Downloads [pkg]@[version] tarball into the pkg dir and returns the file. */
    fun fetchTarball(ctx: Context, pkg: String, version: String, onLine: (String) -> Unit): File? {
        val enc = pkg.replace("/", "%2f")
        val meta = try {
            registryJsonFor(ctx, "${registry(ctx)}/$enc") ?: throw Exception("registry 不可达")
        } catch (e: Exception) {
            onLine("[update] 无法获取 $pkg 元数据：${e.message}")
            return null
        }
        val tarball = try {
            meta.getJSONObject("versions").getJSONObject(version).getJSONObject("dist").getString("tarball")
        } catch (_: Exception) {
            onLine("[update] registry 上没有 $pkg@$version")
            return null
        }
        val safe = pkg.replace("@", "").replace("/", "-")
        val out = File(NodeRuntime.pkgDir(ctx), "$safe-$version.tgz")
        onLine("[update] 下载 $pkg@$version ...")
        if (!download(ctx, tarball, out) { onLine("[update] 下载失败：$it") }) {
            out.delete()
            return null
        }
        onLine("[update] 下载完成：${"%.1f".format(out.length() / 1048576.0)}MB")
        return out
    }

    /**
     * Installs [tgzFile] into the standby slot (A/B). The new version becomes
     * active on the next app/agent boot; if that boot fails, the previous tree
     * is restored automatically. zcode activates immediately (no boot to
     * verify against); dsh waits for the next DshService start.
     */
    fun applyUpdate(
        ctx: Context,
        pkg: String,
        tgzFile: File,
        onLine: (String) -> Unit,
    ): Boolean {
        val node = NodeRuntime.nodeBin(ctx)
        val npm = NodeRuntime.npmCliJs(ctx)
        val ignoreScripts = pkg != "zcode-app-cli"
        val short = if (pkg == "zcode-app-cli") AgentSlots.ZCODE else AgentSlots.DSH
        clearMarkers(ctx, pkg)
        val version = AgentSlots.installUpdate(ctx, short, tgzFile, node, npm, ignoreScripts, onLine)
            ?: run {
                onLine("[update] $pkg 更新失败，当前版本仍可用")
                return false
            }
        if (pkg == "@deepseek-ai/dsh") {
            // patch the standby tree BEFORE it becomes active, so the
            // verification boot runs the fully adapted build
            val dshRoot = AgentSlots.read(ctx, short)?.let { s ->
                File(AgentSlots.slotPrefix(ctx, short, s.standby ?: return@let null), "lib/node_modules/@deepseek-ai/dsh")
            }
            injectPty(ctx, onLine, dshRoot)
            sharpWasm(ctx, node, npm, NodeRuntime.usrDir(ctx), onLine, dshRoot)
            runAndroidPatches(ctx, onLine, dshRoot)
        }
        onLine("[update] $pkg $version 更新完成 ✅")
        return true
    }

    /** Markers carry the packaged version, so match by prefix. */
    private fun clearMarkers(ctx: Context, pkg: String) {
        val prefix = if (pkg == "zcode-app-cli") ".cli-installed-zcode" else ".cli-installed-dsh"
        NodeRuntime.usrDir(ctx).listFiles()?.forEach { f ->
            if (f.name.startsWith(prefix)) f.delete()
        }
    }

    /**
     * Re-applies every Android patch to the freshly installed tree: an update
     * replaces the package files, so without this the flock stub, the link()
     * fallback, the sandbox platform chain and the ripgrep shim are all lost.
     */
    private fun runAndroidPatches(ctx: Context, onLine: (String) -> Unit, dshRoot: File? = null) =
        Bootstrap.applyAndroidPatches(ctx, onLine, dshRoot)

    private fun injectPty(ctx: Context, onLine: (String) -> Unit, dshRoot: File? = null) {
        val src = File(NodeRuntime.pkgDir(ctx), "node-pty-prebuild/pty.node")
        val dir = File(
            dshRoot ?: File(NodeRuntime.usrDir(ctx), "lib/node_modules/@deepseek-ai/dsh"),
            "node_modules/node-pty/prebuilds/android-arm64",
        )
        if (src.exists()) {
            dir.mkdirs()
            src.copyTo(File(dir, "pty.node"), overwrite = true)
            onLine("[update] 已重新注入 node-pty 安卓预编译。")
        }
    }

    private fun sharpWasm(ctx: Context, node: File, npm: File, usr: File, onLine: (String) -> Unit, dshRoot: File? = null) {
        try {
            val sharpDir = File(dshRoot ?: File(usr, "lib/node_modules/@deepseek-ai/dsh"), "node_modules/sharp")
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

    /** Manual rollback from Settings: swap back to the standby slot tree. */
    fun rollback(ctx: Context, pkg: String, onLine: (String) -> Unit): Boolean {
        val short = if (pkg == "zcode-app-cli") AgentSlots.ZCODE else AgentSlots.DSH
        return AgentSlots.rollback(ctx, short, onLine)
    }

    /** Binary agents (codex/claude): download platform tgz to pkg and re-extract. */
    fun updateBinaryAgent(ctx: Context, agent: String, version: String, onLine: (String) -> Unit): Boolean {
        val tarball = when (agent) {
            BinaryAgents.CODEX -> "${registry(ctx)}/@openai/codex/-/codex-$version-linux-arm64.tgz"
            BinaryAgents.CLAUDE -> "${registry(ctx)}/@anthropic-ai%2fclaude-code-linux-arm64-musl/-/claude-code-linux-arm64-musl-$version.tgz"
            BinaryAgents.OPENCODE -> "${registry(ctx)}/opencode-linux-arm64-musl/-/opencode-linux-arm64-musl-$version.tgz"
            else -> return false
        }
        val out = File(NodeRuntime.pkgDir(ctx), "$agent.tgz")
        onLine("[update] 下载 $agent@$version ...")
        if (!download(ctx, tarball, out) { onLine("[update] 下载失败：$it") }) return false
        onLine("[update] 下载完成：${"%.1f".format(out.length() / 1048576.0)}MB")
        File(NodeRuntime.pkgDir(ctx), ".$agent-version").delete()
        val ok = BinaryAgents.ensure(ctx, agent, onLine)
        if (ok) {
            onLine("[update] $agent 更新完成 ✅")
        }
        return ok
    }
}
