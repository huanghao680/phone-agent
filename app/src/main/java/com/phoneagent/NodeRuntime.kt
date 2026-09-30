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
        pruneStaleRuntimeFiles(ctx)
    }

    /**
     * Removes runtime files that this build no longer ships.
     *
     * Extraction overwrites the files it ships but never deletes ones it has
     * stopped shipping, so a package dropped from the staging list (curl was
     * one) stays on disk as a stale binary linked against older libraries —
     * on Android that surfaced as "cannot locate symbol SSL_get_ex_new_index"
     * because the linker fell back to /system/lib64/libssl.so.
     *
     * Only paths named by the PREVIOUS manifest are candidates, so npm-installed
     * CLI shims in usr/bin (zcode, dsh, ...) and our own additions (bwrap,
     * proot, landlock-wrap) are never touched.
     */
    private fun pruneStaleRuntimeFiles(ctx: Context) {
        val previous = File(ctx.filesDir, ".runtime-manifest")
        val current = try {
            ctx.assets.open("runtime.manifest").bufferedReader().use { it.readLines().toSet() }
        } catch (_: IOException) {
            return // older build without a manifest: nothing to compare against
        }
        if (previous.exists()) {
            previous.readLines().filter { it.isNotBlank() && it !in current }.forEach { rel ->
                val f = File(ctx.filesDir, rel)
                if (f.isFile) {
                    try {
                        Files.deleteIfExists(f.toPath())
                    } catch (_: IOException) {
                        // in use or read-only: leaving it is no worse than before
                    }
                }
            }
        }
        previous.writeText(current.sorted().joinToString("\n"))
    }

    /** Copies the bundled CLI tarballs from APK assets to a real path npm can read. */
    fun copyPackages(ctx: Context) {
        ensureSandboxTools(ctx)
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
        // dsh Android patch entry point + its Node-based patcher
        for (name in arrayOf("patch-dsh-flock.sh", "patch-dsh-android.mjs")) {
            try {
                ctx.assets.open("scripts/$name").use { input ->
                    val dir = File(usrDir(ctx), "share/phone-agent").apply { mkdirs() }
                    val out = File(dir, name)
                    FileOutputStream(out).use { input.copyTo(it, 1 shl 16) }
                    out.setExecutable(true, false)
                }
            } catch (_: IOException) {
                // asset absent: matching dsh patch skipped
            }
        }
        // codex / claude npm platform tarballs + musl loader for the terminal
        // picker (optional until fetch-terminal-extra.sh runs in CI)
        for (name in arrayOf(
            "codex.tgz", "claude.tgz", "ld-musl-aarch64.so.1", "versions.json", "rg",
            // opencode: Bun executable + the musl loader / C++ runtime it needs
            "opencode.tgz", "libstdc++.so.6", "libgcc_s.so.1",
        )) {
            try {
                ctx.assets.open("terminal-extra/$name").use { input ->
                    val out = File(pkgDir(ctx), name)
                    FileOutputStream(out).use { input.copyTo(it, 1 shl 16) }
                }
            } catch (_: IOException) {
                // asset absent: picker shows the entries as unavailable
            }
        }
    }

    /**
     * Installs the dsh bash-sandbox shim (usr/bin/bwrap, backed by proot from
     * the runtime + the CI-built landlock-wrap when present) and records the
     * permission mode dsh profiles should default to. workspace-write requires
     * at least the universal proot backend; without it agents keep running
     * with danger-full-access so bash never fails closed.
     */
    fun ensureSandboxTools(ctx: Context) {
        val usr = usrDir(ctx)
        val bin = File(usr, "bin").apply { mkdirs() }
        try {
            ctx.assets.open("scripts/bwrap").use { input ->
                val out = File(bin, "bwrap")
                FileOutputStream(out).use { input.copyTo(it, 1 shl 16) }
                out.setExecutable(true, false)
            }
        } catch (_: IOException) {
            // asset absent (stale checkout): sandbox shim not installed
        }
        try {
            ctx.assets.open("native/landlock-wrap").use { input ->
                val out = File(bin, "landlock-wrap")
                FileOutputStream(out).use { input.copyTo(it, 1 shl 16) }
                out.setExecutable(true, false)
            }
        } catch (_: IOException) {
            // asset absent: proot tier remains as the fallback backend
        }
        val mode =
            if (File(bin, "bwrap").exists() && File(usr, "bin/proot").exists()) "workspace-write"
            else "danger-full-access"
        File(usr, "share/phone-agent").apply { mkdirs() }
        File(usr, "share/phone-agent/sandbox-mode").writeText(mode)
        // pnpm install needs the network; keep it off the boot path
        kotlin.concurrent.thread(name = "pnpm-install") { ensurePnpm(ctx) }
    }

    /**
     * Installs the pinned pnpm 10 the dsh plugin manager shells out to
     * (`pnpm view/add` in the profile directory). pnpm 12 is unusable here: its
     * native executable takes flock-based store locks the filesystem does not
     * support, and corepack no longer ships with Node. No-op when the pinned
     * version is already present.
     */
    private fun ensurePnpm(ctx: Context) {
        val usr = usrDir(ctx)
        val manifest = File(usr, "lib/node_modules/pnpm/package.json")
        if (manifest.exists()) {
            val current = try {
                org.json.JSONObject(manifest.readText()).optString("version")
            } catch (_: Exception) {
                ""
            }
            if (current == Versions.PNPM_VERSION) return
        }
        val node = File(usr, "bin/node")
        val npm = File(usr, "lib/node_modules/npm/bin/npm-cli.js")
        if (!node.canExecute() || !npm.exists()) return
        try {
            val pb = ProcessBuilder(
                node.absolutePath, npm.absolutePath,
                "install", "-g", "--prefix", usr.absolutePath,
                "pnpm@${Versions.PNPM_VERSION}",
            )
            pb.environment().putAll(environment(ctx))
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.use { it.readBytes() } // drain; the caller logs elsewhere
            p.waitFor()
        } catch (_: Exception) {
            // offline first boot: the Plugins page will surface the missing
            // pnpm on its own; the next boot retries
        }
    }

    /**
     * Seeds the must-read environment description (AGENTS.md) that both CLIs
     * auto-load into context: ~/.dsh/AGENTS.md is dsh's global instructions
     * file, the workspace root AGENTS.md is read by zcode (and dsh). Only
     * written when missing — the user may edit their copy.
     */
    fun seedAgentInstructions(ctx: Context) {
        val body = try {
            ctx.assets.open("agents/AGENTS.md").bufferedReader().use { it.readText() }
        } catch (_: IOException) {
            return
        }
        for (target in listOf(
            File(homeDir(ctx), ".dsh/AGENTS.md"),
            File(homeDir(ctx), "AGENTS.md"),
        )) {
            if (!target.exists()) {
                target.parentFile?.mkdirs()
                try {
                    target.writeText(body)
                } catch (_: IOException) {
                    // read-only home is not a thing here; ignore anyway
                }
            }
        }
    }

    fun environment(ctx: Context, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val usr = usrDir(ctx).absolutePath
        val env = mutableMapOf(
            "PATH" to "$usr/bin:/system/bin:/system/xbin:/vendor/bin",
            "LD_LIBRARY_PATH" to "$usr/lib",
            "HOME" to homeDir(ctx).absolutePath,
            // dsh's spill-local plugin mkdtemps in os.tmpdir(), which falls back
            // to /data/local/tmp (EACCES for apps) unless TMPDIR is set here.
            "TMPDIR" to ctx.cacheDir.absolutePath,
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            // Android's default SHELL is /bin/sh (mksh); agents that inspect it
            // should see the shell they actually get
            "SHELL" to "$usr/bin/bash",
            "NODE_PATH" to "$usr/lib/node_modules",
            "npm_config_registry" to Prefs.npmRegistry(ctx),
            // The Termux-built Node hardcodes /data/data/com.termux/.../openssl.cnf;
            // outside Termux that path is unreadable and TLS init fails, so point
            // OpenSSL at the config shipped with our runtime.
            "OPENSSL_CONF" to "$usr/etc/tls/openssl.cnf",
            // curl/wget compiled for Termux look for CAs under the Termux prefix;
            // Node's own fetch uses bundled CAs and is unaffected.
            "CURL_CA_BUNDLE" to "$usr/etc/tls/cert.pem",
            "SSL_CERT_FILE" to "$usr/etc/tls/cert.pem",
            // git is compiled with the Termux prefix baked in, so it looks for
            // its subcommands and templates where Termux would keep them
            // (git-upload-pack was "No such file or directory" without this).
            "GIT_EXEC_PATH" to "$usr/libexec/git-core",
            "GIT_TEMPLATE_DIR" to "$usr/share/git-core/templates",
            // git's libcurl uses the CA path baked in at build time and ignores
            // CURL_CA_BUNDLE, so https clones fail with "error adding trust
            // anchors". GIT_CONFIG_* injects the setting without touching any
            // config file.
            "GIT_CONFIG_COUNT" to "1",
            "GIT_CONFIG_KEY_0" to "http.sslCAInfo",
            "GIT_CONFIG_VALUE_0" to "$usr/etc/tls/cert.pem",
            // dsh bash sandbox mode: written by ensureSandboxTools —
            // workspace-write when a sandbox backend is staged (bwrap shim +
            // proot), otherwise danger-full-access so bash never fails closed.
            // The dsh web GUI session presets danger-full-access itself.
            "DSH_PERMISSION_MODE" to runCatching {
                File(usr, "share/phone-agent/sandbox-mode").readText().trim()
            }.getOrNull().orEmpty().ifEmpty { "danger-full-access" },
        )
        env.putAll(Workspace.envOverlay(ctx))
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
