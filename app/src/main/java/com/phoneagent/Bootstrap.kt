package com.phoneagent

import android.content.Context
import java.io.File

/**
 * First-run CLI installer: npm-install the bundled tarballs into the embedded
 * prefix. Used headlessly by DshService; the Zcode terminal runs the same
 * logic visibly through SetupScripts.zcodeScript().
 *
 * zcode installs normally (self-contained). dsh installs with --ignore-scripts
 * because its node-pty dependency cannot compile on-device; the CI-built
 * android-arm64 binding is injected afterwards from assets.
 */
object Bootstrap {

    fun isZcodeInstalled(ctx: Context): Boolean = NodeRuntime.isZcodeInstalled(ctx)
    fun isDshInstalled(ctx: Context): Boolean = NodeRuntime.isDshInstalled(ctx)

    /**
     * True when the packaged CLI version differs from what is on disk — the
     * entry file merely existing is not enough, or a version bump shipped in a
     * new APK would never reach an already-installed device.
     *
     * A manual update to a NEWER version wins over the APK-pinned one: the
     * pinned reinstall only fires when the active tree is missing or OLDER
     * than the packaged version, never as a downgrade.
     */
    fun zcodeNeedsInstall(ctx: Context): Boolean =
        AgentSlots.activeNeedsPinned(ctx, AgentSlots.ZCODE, Versions.ZCODE)

    fun dshNeedsInstall(ctx: Context): Boolean =
        AgentSlots.activeNeedsPinned(ctx, AgentSlots.DSH, Versions.DSH)

    /** Blocking; call from a worker thread. Streams npm output lines to [onLine]. */
    fun install(ctx: Context, onLine: (String) -> Unit): Boolean {
        NodeRuntime.copyPackages(ctx)
        val usr = NodeRuntime.usrDir(ctx)
        val node = NodeRuntime.nodeBin(ctx)
        val npm = NodeRuntime.npmCliJs(ctx)
        if (!node.canExecute() || !npm.exists()) {
            onLine("[phone-agent] 运行时不完整，无法安装组件")
            return false
        }

        val zcodeOk = npmInstall(ctx, node, npm, usr, AgentSlots.ZCODE, Versions.ZCODE_TGZ, Versions.ZCODE, ignoreScripts = false, onLine = onLine)
        if (zcodeOk) marker(ctx, "zcode").writeText("ok")
        val dshOk = npmInstall(ctx, node, npm, usr, AgentSlots.DSH, Versions.DSH_TGZ, Versions.DSH, ignoreScripts = true, onLine = onLine)
        if (dshOk) marker(ctx, "dsh").writeText("ok")
        if (dshOk) {
            injectPtyPrebuild(ctx, onLine)
            installSharpWasm(ctx, node, npm, usr, onLine)
            // the freshly unpacked dsh tree has none of the Android fixes
            applyAndroidPatches(ctx, onLine)
        }
        return dshOk // the dsh service only needs dsh; zcode is for the terminal
    }

    /**
     * Runs the Android compatibility patcher over the installed tree: flock
     * stub, link() fallback, sandbox platform chain, attachment fsync
     * tolerance, ripgrep shim and shebang prefixes. Every (re)install replaces
     * those files, so this runs after each install and self-update.
     */
    fun applyAndroidPatches(ctx: Context, onLine: (String) -> Unit, dshRoot: File? = null) {
        val script = File(NodeRuntime.usrDir(ctx), "share/phone-agent/patch-dsh-flock.sh")
        if (!script.exists()) {
            onLine("[phone-agent] 未找到补丁脚本，跳过 Android 适配")
            return
        }
        try {
            val pb = ProcessBuilder("/system/bin/sh", script.absolutePath)
            pb.environment().putAll(NodeRuntime.environment(ctx))
            dshRoot?.let { pb.environment()["DSH_ROOT"] = it.absolutePath }
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.bufferedReader().forEachLine(onLine)
            if (p.waitFor() != 0) onLine("[phone-agent] 补丁脚本返回非零（部分适配可能未生效）")
        } catch (e: Exception) {
            onLine("[phone-agent] 补丁脚本执行异常：${e.message}")
        }
    }

    private fun marker(ctx: Context, name: String): File =
        File(NodeRuntime.usrDir(ctx), ".cli-installed-$name")

    /**
     * Installs the packaged tarball into the ACTIVE slot of [pkg] (or into the
     * legacy usr layout when slots have never been used). Only fires when the
     * active tree is missing or OLDER than the packaged version — a manual
     * update to a newer version is never downgraded by the pinned reinstall.
     */
    private fun npmInstall(
        ctx: Context,
        node: File,
        npm: File,
        usr: File,
        pkg: String,
        tgz: String,
        wantVersion: String,
        ignoreScripts: Boolean,
        onLine: (String) -> Unit,
    ): Boolean {
        if (!AgentSlots.activeNeedsPinned(ctx, pkg, wantVersion)) return true
        AgentSlots.migrateLegacy(ctx, pkg)
        val slotPrefix = AgentSlots.slotPrefix(ctx, pkg, AgentSlots.read(ctx, pkg)?.active ?: "a")
        val manifest = File(AgentSlots.legacyDir(ctx, pkg), "package.json")
        // Reinstall when the staged tarball is newer than what is on disk: the
        // entry file existing is not enough, or a packaged version bump would
        // never reach an already-installed device.
        val installed = if (manifest.exists()) installedVersion(manifest) else null
        if (installed == wantVersion) return true
        if (installed != null) onLine("[phone-agent] 组件更新 $installed -> $wantVersion（$tgz）")
        val args = mutableListOf(
            node.absolutePath, npm.absolutePath,
            "install", "-g", "--prefix", slotPrefix.absolutePath,
        )
        if (ignoreScripts) args.add("--ignore-scripts")
        args.add("file:${File(NodeRuntime.pkgDir(ctx), tgz).absolutePath}")
        val pb = ProcessBuilder(args)
        pb.environment().putAll(NodeRuntime.environment(ctx))
        pb.redirectErrorStream(true)
        return try {
            val p = pb.start()
            p.inputStream.bufferedReader().forEachLine(onLine)
            val code = p.waitFor()
            if (code != 0) onLine("[phone-agent] npm 退出码 $code（$tgz）")
            if (code == 0) {
                // record the slot state (first install creates it) and expose
                // the tree through the npm-global symlink
                val version = installedVersion(File(AgentSlots.slotPackageDir(ctx, pkg, AgentSlots.read(ctx, pkg)?.active ?: "a"), "package.json"))
                val s = AgentSlots.read(ctx, pkg)
                if (s == null) {
                    AgentSlots.initActive(ctx, pkg, version)
                } else {
                    AgentSlots.setActiveVersion(ctx, pkg, version)
                    onLine("[phone-agent] $pkg 现役槽已更新到 $version")
                }
            }
            code == 0
        } catch (e: Exception) {
            onLine("[phone-agent] 安装异常：${e.message}")
            false
        }
    }

    /** Reads the "version" field from an installed package's package.json. */
    private fun installedVersion(manifest: File): String? = try {
        org.json.JSONObject(manifest.readText()).optString("version").ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    /** Copies the CI-built android-arm64 pty.node into dsh's node-pty prebuilds dir. */
    private fun injectPtyPrebuild(ctx: Context, onLine: (String) -> Unit) {
        val src = File(NodeRuntime.pkgDir(ctx), "node-pty-prebuild/pty.node")
        if (!src.exists()) {
            onLine("[phone-agent] 未找到 node-pty 预编译（dsh 网页终端将不可用）")
            return
        }
        val dir = File(
            NodeRuntime.usrDir(ctx),
            "lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty/prebuilds/android-arm64",
        )
        dir.mkdirs()
        src.copyTo(File(dir, "pty.node"), overwrite = true)
        onLine("[phone-agent] 已注入 node-pty 安卓预编译。")
    }

    /**
     * sharp publishes no android-arm64 binary. Its loader looks for
     * @img/sharp-wasm32 inside sharp's OWN node_modules tree, so the wasm32
     * package must be installed there (a global install is not found).
     */
    private fun installSharpWasm(ctx: Context, node: File, npm: File, usr: File, onLine: (String) -> Unit) {
        val sharpDir = File(usr, "lib/node_modules/@deepseek-ai/dsh/node_modules/sharp")
        val sharpPkg = File(sharpDir, "package.json")
        if (!sharpPkg.exists()) return
        try {
            val ver = org.json.JSONObject(sharpPkg.readText()).getString("version")
            if (File(sharpDir, "node_modules/@img/sharp-wasm32/package.json").exists()) return
            onLine("[phone-agent] 安装 @img/sharp-wasm32@$ver 到 sharp 本地依赖树 ...")
            val pb = ProcessBuilder(
                node.absolutePath, npm.absolutePath,
                "install", "--prefix", sharpDir.absolutePath,
                "--no-save", "--no-package-lock", "--ignore-scripts",
                "@img/sharp-wasm32@$ver",
            )
            pb.environment().putAll(NodeRuntime.environment(ctx))
            pb.directory(sharpDir)
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.bufferedReader().forEachLine(onLine)
            val code = p.waitFor()
            onLine(
                if (code == 0) "[phone-agent] sharp-wasm32 安装完成。"
                else "[phone-agent] sharp-wasm32 安装失败（退出码 $code）"
            )
        } catch (e: Exception) {
            onLine("[phone-agent] sharp-wasm32 安装失败（dsh 图片功能受限）：${e.message}")
        }
    }

    /** Same fix for the terminal bootstrap path (SetupScripts mirrors this logic). */
    fun sharpWasmCommand(sharpDir: File, ver: String): List<String> = listOf(
        "install", "--prefix", sharpDir.absolutePath,
        "--no-save", "--no-package-lock", "--ignore-scripts",
        "@img/sharp-wasm32@$ver",
    )
}
