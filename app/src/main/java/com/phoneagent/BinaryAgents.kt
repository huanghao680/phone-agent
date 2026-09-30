package com.phoneagent

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Extracts and manages the codex / claude standalone binaries from their npm
 * platform tarballs (assets/terminal-extra), and checks the registry for
 * newer versions so the terminal picker can update them in place —
 * same lifecycle as zcode/dsh, just raw binaries instead of npm installs.
 */
object BinaryAgents {

    const val CODEX = "codex"
    const val CLAUDE = "claude"
    const val OPENCODE = "opencode"

    data class Info(val name: String, val installed: String?, val latest: String) {
        val updateAvailable: Boolean get() = installed != null && installed != latest
    }

    private fun pkg(ctx: Context): File = NodeRuntime.pkgDir(ctx)

    fun isReady(ctx: Context, agent: String): Boolean = when (agent) {
        CODEX -> File(pkg(ctx), "vendor/aarch64-unknown-linux-musl/bin/codex").exists()
        CLAUDE -> File(pkg(ctx), "claude").exists() && File(pkg(ctx), "ld-musl-aarch64.so.1").exists()
        // opencode is a Bun single-file executable: it needs the musl loader and
        // the GNU C++ runtime it was linked against (both shipped as assets)
        OPENCODE -> File(pkg(ctx), "opencode").exists() &&
            File(pkg(ctx), "ld-musl-aarch64.so.1").exists() &&
            File(pkg(ctx), "libstdc++.so.6").exists()
        else -> false
    }

    private fun assetTgz(ctx: Context, agent: String): File = File(pkg(ctx), "$agent.tgz")

    private fun marker(ctx: Context, agent: String): File = File(pkg(ctx), ".$agent-version")

    fun installedVersion(ctx: Context, agent: String): String? {
        val m = marker(ctx, agent)
        return if (m.exists()) m.readText().trim() else null
    }

    fun bundledVersion(ctx: Context, agent: String): String? = try {
        val mf = File(pkg(ctx), "versions.json")
        if (mf.exists()) org.json.JSONObject(mf.readText()).getString(agent) else null
    } catch (_: Exception) {
        null
    }

    /**
     * Ensures the binary is extracted. Runs on a worker thread; writes progress
     * to [onLine]. Returns true when the binary is present and executable.
     *
     * Codex needs its full vendor/ tree (rg, sandbox, voice resources) alongside
     * the binary, so the whole package/vendor directory is preserved relative
     * to the extracted binary. Claude is a single loader-launched binary.
     */
    fun ensure(ctx: Context, agent: String, onLine: (String) -> Unit): Boolean {
        if (isReady(ctx, agent)) return true
        val tgz = assetTgz(ctx, agent)
        if (!tgz.exists()) {
            onLine("[binary] $agent 未打包（当前构建未包含）")
            return false
        }
        onLine("[binary] 解压 $agent ...")
        try {
            GZIPInputStream(tgz.inputStream()).use { gz ->
                TarArchiveInputStream(gz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val name = entry.name // package/...
                        if (!entry.isDirectory) {
                            when (agent) {
                                // codex needs its full vendor tree (rg, sandbox,
                                // voice companions) next to the binary
                                CODEX -> if (name.startsWith("package/vendor/")) {
                                    val out = File(pkg(ctx), name.removePrefix("package/"))
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                                    out.setExecutable(true, false)
                                }
                                // claude is a single binary launched by the loader
                                CLAUDE -> if (name == "package/claude") {
                                    val out = File(pkg(ctx), "claude")
                                    out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                                    out.setExecutable(true, false)
                                }
                                // opencode ships a single Bun executable; the
                                // loader and C++ runtime come from assets
                                OPENCODE -> if (name == "package/bin/opencode") {
                                    val out = File(pkg(ctx), "opencode")
                                    out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                                    out.setExecutable(true, false)
                                }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            onLine("[binary] 解压失败：${e.message}")
            return false
        }
        val ver = bundledVersion(ctx, agent) ?: "unknown"
        marker(ctx, agent).writeText(ver)
        return isReady(ctx, agent)
    }

    /** Latest version from the npm registry, resolving each agent's platform package. */
    fun latestVersion(ctx: Context, agent: String): String? = try {
        // codex's platform binaries are aliased versions of the main package
        // (e.g. @openai/codex@0.157.1-linux-arm64), so the main package's
        // version determines the family; claude publishes a separate musl pkg
        val url = when (agent) {
            CODEX -> "${Prefs.npmRegistry(ctx).trimEnd('/')}/@openai%2fcodex/${Versions.CODEX_VERSION}"
            CLAUDE -> "${Prefs.npmRegistry(ctx).trimEnd('/')}/@anthropic-ai%2fclaude-code-linux-arm64-musl/${Versions.CLAUDE_VERSION}"
            else -> "${Prefs.npmRegistry(ctx).trimEnd('/')}/opencode-linux-arm64-musl/${Versions.OPENCODE_VERSION}"
        }
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        JSONObjectText(conn.inputStream.bufferedReader().use { it.readText() }, agent)
    } catch (_: Exception) {
        null
    }

    private fun JSONObjectText(body: String, agent: String): String? = try {
        val j = org.json.JSONObject(body)
        val v = j.getString("version")
        when (agent) {
            // main version 0.157.1 -> platform version 0.157.1-linux-arm64 family
            CODEX -> v.substringBefore('-')
            else -> v
        }
    } catch (_: Exception) {
        null
    }
}
