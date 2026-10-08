package com.phoneagent

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * A/B slot management for the two agent packages (zcode, dsh) — the same
 * contract as Android system updates: an update installs into the standby
 * slot, becomes active on the next boot, and is automatically reverted if that
 * boot fails; a manual rollback swaps the slots back.
 *
 * The active slot is exposed through the npm-global symlink
 * usr/lib/node_modules/<pkg> → files/agents/<pkg>/<slot>/lib/node_modules/<pkg>,
 * so every existing consumer (entry paths, bootstrap scripts, the bwrap
 * wrapper, the Android patcher) keeps working unchanged across switches.
 */
object AgentSlots {
    const val ZCODE = "zcode"
    const val DSH = "dsh"

    private fun root(ctx: Context, pkg: String): File = File(ctx.filesDir, "agents/$pkg")
    private fun metaFile(ctx: Context, pkg: String): File = File(root(ctx, pkg), "slot.json")

    fun packageName(pkg: String): String = if (pkg == ZCODE) "zcode-app-cli" else "@deepseek-ai/dsh"
    fun legacyDir(ctx: Context, pkg: String): File = File(NodeRuntime.usrDir(ctx), "lib/node_modules/${packageName(pkg)}")
    fun slotPackageDir(ctx: Context, pkg: String, slot: String): File =
        File(slotPrefix(ctx, pkg, slot), "lib/node_modules/${packageName(pkg)}")
    fun slotPrefix(ctx: Context, pkg: String, slot: String): File = File(root(ctx, pkg), slot)

    class State(
        val active: String,
        val activeVersion: String?,
        val standby: String?,
        val standbyVersion: String?,
        val state: String, // verified | pending-activate | verifying
    ) {
        val hasStandbyTree: Boolean
            get() = standby != null
    }

    fun read(ctx: Context, pkg: String): State? = try {
        val j = JSONObject(metaFile(ctx, pkg).readText())
        State(
            j.getString("active"),
            j.optString("activeVersion").ifEmpty { null },
            j.optString("standby").ifEmpty { null },
            j.optString("standbyVersion").ifEmpty { null },
            j.optString("state", "verified"),
        )
    } catch (_: Exception) {
        null
    }

    private fun write(ctx: Context, pkg: String, s: State) {
        val f = metaFile(ctx, pkg)
        f.parentFile?.mkdirs()
        f.writeText(
            JSONObject().apply {
                put("active", s.active)
                put("activeVersion", s.activeVersion ?: "")
                put("standby", s.standby ?: "")
                put("standbyVersion", s.standbyVersion ?: "")
                put("state", s.state)
            }.toString(),
        )
    }

    /**
     * Moves a legacy in-place install (usr/lib/node_modules/<pkg>) into slot "a"
     * and points the npm-global symlink at it. Same-filesystem rename, so it is
     * instant. Safe to call repeatedly; skips when slots are already in use.
     */
    fun migrateLegacy(ctx: Context, pkg: String) {
        if (read(ctx, pkg) != null) return
        val legacy = legacyDir(ctx, pkg)
        if (!legacy.exists() || java.nio.file.Files.isSymbolicLink(legacy.toPath())) return
        val dest = slotPackageDir(ctx, pkg, "a")
        dest.parentFile?.mkdirs()
        try {
            java.nio.file.Files.move(legacy.toPath(), dest.toPath())
        } catch (_: Exception) {
            return // move failed: keep the legacy layout, slots stay unused
        }
        val version = runCatching {
            JSONObject(File(dest, "package.json").readText()).optString("version").ifEmpty { null }
        }.getOrNull()
        write(ctx, pkg, State("a", version, null, null, "verified"))
        linkActive(ctx, pkg)
    }

    /** Points usr/lib/node_modules/<pkg> at the given slot's package dir. */
    private fun linkActive(ctx: Context, pkg: String) {
        val s = read(ctx, pkg) ?: return
        val link = legacyDir(ctx, pkg)
        val target = slotPackageDir(ctx, pkg, s.active)
        link.parentFile?.mkdirs()
        val linkIsSymlink = java.nio.file.Files.isSymbolicLink(link.toPath())
        if (linkIsSymlink || (link.exists() && !link.isDirectory)) {
            link.delete()
        } else if (link.isDirectory) {
            // A real directory left behind by an interrupted migration can never
            // be replaced by delete() alone, and while it sits there every boot
            // reads the tree as "not the pinned install" and reinstalls (measured:
            // +6s cold start). Swap it for the symlink the slots expect.
            if (!link.deleteRecursively()) return
        }
        if (!link.exists()) {
            runCatching {
                java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
            }
        }
    }

    /**
     * Installs [tgzFile] into the slot that is NOT currently active and marks
     * it pending-activate. Returns the installed version, or null on failure.
     */
    fun installUpdate(
        ctx: Context,
        pkg: String,
        tgzFile: File,
        node: File,
        npm: File,
        ignoreScripts: Boolean,
        onLine: (String) -> Unit,
    ): String? {
        migrateLegacy(ctx, pkg)
        val s = read(ctx, pkg) ?: State("a", null, null, null, "verified")
        val activeTree = legacyDir(ctx, pkg)
        if (!activeTree.exists()) {
            // nothing active yet (fresh device): install straight into the
            // active slot — there is nothing to roll back to anyway
            val prefix = slotPrefix(ctx, pkg, s.active)
            File(prefix, "lib").mkdirs()
            val args = mutableListOf(node.absolutePath, npm.absolutePath, "install", "-g", "--prefix", prefix.absolutePath)
            if (ignoreScripts) args.add("--ignore-scripts")
            args.add("file:${tgzFile.absolutePath}")
            val p = ProcessBuilder(args).also { it.environment().putAll(NodeRuntime.environment(ctx)) }.redirectErrorStream(true).start()
            p.inputStream.bufferedReader().forEachLine(onLine)
            if (p.waitFor() != 0) {
                onLine("[update] 安装失败")
                return null
            }
            val version = runCatching {
                JSONObject(File(slotPackageDir(ctx, pkg, s.active), "package.json").readText()).optString("version")
            }.getOrNull().orEmpty()
            write(ctx, pkg, State(s.active, version.ifEmpty { null }, null, null, "verified"))
            linkActive(ctx, pkg)
            onLine("[update] $pkg $version 安装完成")
            return version.ifEmpty { null }
        }
        val target = if (s.active == "a") "b" else "a"
        val prefix = slotPrefix(ctx, pkg, target)
        prefix.deleteRecursively() // a previous pending/standby tree is replaced
        File(prefix, "lib").mkdirs()
        val args = mutableListOf(node.absolutePath, npm.absolutePath, "install", "-g", "--prefix", prefix.absolutePath)
        if (ignoreScripts) args.add("--ignore-scripts")
        args.add("file:${tgzFile.absolutePath}")
        onLine("[update] 安装到备用槽 $target ...")
        return try {
            val pb = ProcessBuilder(args)
            pb.environment().putAll(NodeRuntime.environment(ctx))
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.bufferedReader().forEachLine(onLine)
            if (p.waitFor() != 0) {
                onLine("[update] 备用槽安装失败，当前版本不受影响")
                prefix.deleteRecursively()
                return null
            }
            val version = runCatching {
                JSONObject(File(slotPackageDir(ctx, pkg, target), "package.json").readText()).optString("version")
            }.getOrNull().orEmpty()
            if (version.isEmpty()) {
                onLine("[update] 备用槽安装结果异常（缺少 package.json）")
                return null
            }
            write(ctx, pkg, State(s.active, s.activeVersion, target, version, "pending-activate"))
            onLine("[update] $pkg $version 已就绪，下次启动 App 时生效（启动失败自动回滚）")
            version
        } catch (e: Exception) {
            onLine("[update] 备用槽安装异常：${e.message}")
            null
        }
    }

    /**
     * dsh boot hook: activates a pending slot ("seamless" switch) and reports
     * whether this boot is a verification run. Returns the number of times the
     * boot has been attempted for the current candidate (0 when none).
     */
    fun beginBoot(ctx: Context, pkg: String, onLine: (String) -> Unit): Int {
        val s = read(ctx, pkg) ?: return 0
        if (s.state == "pending-activate" && s.standby != null) {
            write(ctx, pkg, State(s.standby, s.standbyVersion, s.active, s.activeVersion, "verifying"))
            linkActive(ctx, pkg)
            onLine("[phone-agent] 已切换到 ${packageName(pkg)} ${s.standbyVersion}，本启动为验证启动")
            return 1
        }
        return if (s.state == "verifying") 1 else 0
    }

    /**
     * Records the version actually present in the active slot. The APK-driven
     * upgrade installs into the active slot while a State already exists;
     * without this the recorded activeVersion goes stale and every boot would
     * re-run the (expensive) pinned reinstall.
     */
    fun setActiveVersion(ctx: Context, pkg: String, version: String) {
        val s = read(ctx, pkg) ?: return
        if (s.activeVersion == version) return
        write(ctx, pkg, State(s.active, version, s.standby, s.standbyVersion, s.state))
    }

    /** Verification succeeded: the previous tree stays as the rollback target. */
    fun markBootOk(ctx: Context, pkg: String) {
        val s = read(ctx, pkg) ?: return
        if (s.state == "verifying") write(ctx, pkg, State(s.active, s.activeVersion, s.standby, s.standbyVersion, "verified"))
    }

    /**
     * Verification failed: repoint to the previous tree so the retry boots the
     * known-good version. Returns true when a revert happened.
     */
    fun revertFailedBoot(ctx: Context, pkg: String, onLine: (String) -> Unit): Boolean {
        val s = read(ctx, pkg) ?: return false
        if (s.state != "verifying") return false
        // active and standby swapped at beginBoot; swap back
        write(ctx, pkg, State(s.standby ?: s.active, s.standbyVersion, s.active, s.activeVersion, "verified"))
        linkActive(ctx, pkg)
        onLine("[phone-agent] 新版本启动失败，已回滚到 ${s.standbyVersion}")
        return true
    }

    /** Manual rollback from Settings: swaps active and standby. */
    fun rollback(ctx: Context, pkg: String, onLine: (String) -> Unit): Boolean {
        migrateLegacy(ctx, pkg)
        val s = read(ctx, pkg) ?: return false
        if (s.standby == null) {
            onLine("[rollback] 没有可回滚的备用版本")
            return false
        }
        val standbyManifest = File(slotPackageDir(ctx, pkg, s.standby), "package.json")
        if (!standbyManifest.exists()) {
            onLine("[rollback] 备用槽不完整，无法回滚")
            return false
        }
        write(ctx, pkg, State(s.standby, s.standbyVersion, s.active, s.activeVersion, "verified"))
        linkActive(ctx, pkg)
        onLine("[rollback] $pkg 已回滚到 ${s.standbyVersion}，重启会话生效")
        return true
    }

    /** True when the active tree is older than [want] (an APK-driven upgrade). */
    fun activeNeedsPinned(ctx: Context, pkg: String, want: String): Boolean {
        migrateLegacy(ctx, pkg)
        val s = read(ctx, pkg)
        android.util.Log.w("agent-slots", "needsPinned $pkg: state=${s?.state} active=${s?.active} v=${s?.activeVersion} want=$want")
        if (s != null && s.state != "verified") return false // an update is in flight
        // A leftover real directory where the active symlink belongs (an
        // interrupted migration) hides the slot tree from this check and used to
        // force a full reinstall on every boot. Repair the link first.
        if (s != null) {
            val link = legacyDir(ctx, pkg)
            if (!java.nio.file.Files.isSymbolicLink(link.toPath())) {
                android.util.Log.w("agent-slots", "needsPinned $pkg: link is a real dir, repairing")
                linkActive(ctx, pkg)
            }
        }
        // NOTE: legacyDir() is the package DIRECTORY; the version manifest is
        // package.json inside it. Reading the directory itself always threw
        // EISDIR here, so installed= was always null and every cold start
        // reinstalled the agent (measured: +6s).
        val manifest = java.io.File(legacyDir(ctx, pkg), "package.json")
        if (!manifest.exists()) {
            android.util.Log.w("agent-slots", "needsPinned $pkg: manifest MISSING at ${manifest.absolutePath}")
            return true
        }
        val installed = try {
            JSONObject(manifest.readText()).optString("version").ifEmpty { null }
        } catch (e: Exception) {
            android.util.Log.w("agent-slots", "needsPinned $pkg: manifest parse FAILED: ${e.message?.take(120)}")
            null
        }
        android.util.Log.w("agent-slots", "needsPinned $pkg: installed=$installed -> needs=${installed == null || compareVersions(installed, want) < 0}")
        return installed == null || compareVersions(installed, want) < 0
    }

    /**
     * Startup integrity check: an agent may have run a global npm install in a
     * session and rewritten the active tree. When the version on disk no longer
     * matches the recorded one, adopt what is really there (so the state file
     * cannot point at a lie) and re-apply the Android patches, which such an
     * install would have wiped.
     */
    fun reconcile(ctx: Context, pkg: String, onLine: (String) -> Unit) {
        migrateLegacy(ctx, pkg)
        val s = read(ctx, pkg) ?: return
        if (s.state != "verified") return // an A/B switch is in flight
        val actual = runCatching {
            JSONObject(File(legacyDir(ctx, pkg), "package.json").readText()).optString("version").ifEmpty { null }
        }.getOrNull() ?: return
        if (actual != s.activeVersion) {
            onLine("[phone-agent] 检测到 $pkg 现役树版本变化：${s.activeVersion} -> $actual（会话内直接改动？）")
            write(ctx, pkg, State(s.active, actual, s.standby, s.standbyVersion, "verified"))
            Bootstrap.applyAndroidPatches(ctx, onLine)
        }
    }

    /** First slot install: records state and points the symlink at it. */
    fun initActive(ctx: Context, pkg: String, version: String?) {
        write(ctx, pkg, State("a", version, null, null, "verified"))
        linkActive(ctx, pkg)
    }

    /** Semver comparison handling prerelease/build suffixes ("0.2.0-rc.2", "3.14.4-29"). */
    fun compareVersions(a: String, b: String): Int {
        fun core(s: String) = s.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        fun pre(s: String) = s.substringAfter('-', "").takeIf { it.isNotEmpty() }?.split('.')
        val ca = core(a); val cb = core(b)
        for (i in 0..2) {
            val x = ca.getOrElse(i) { 0 }; val y = cb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        val pa = pre(a); val pb = pre(b)
        if (pa == null && pb == null) return 0
        if (pa == null) return 1 // a release outranks its own prereleases
        if (pb == null) return -1
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { return -1 }; val y = pb.getOrElse(i) { return 1 }
            val xn = x.toIntOrNull(); val yn = y.toIntOrNull()
            if (xn != null && yn != null) {
                if (xn != yn) return xn - yn
            } else if (x != y) {
                return x.compareTo(y)
            }
        }
        return 0
    }
}
