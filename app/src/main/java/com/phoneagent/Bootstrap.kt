package com.phoneagent

import android.content.Context
import java.io.File

/**
 * First-run CLI installer: npm-install the bundled tarballs into the embedded
 * prefix. Used headlessly by DshService; the Zcode terminal runs the same
 * logic visibly through SetupScripts.zcodeScript().
 */
object Bootstrap {

    fun isInstalled(ctx: Context): Boolean =
        NodeRuntime.zcodeEntryJs(ctx).exists() && NodeRuntime.dshEntryJs(ctx).exists()

    /**
     * Blocking; call from a worker thread. Streams npm output lines to [onLine]
     * and returns true on success (leaving the version marker behind).
     */
    fun install(ctx: Context, onLine: (String) -> Unit): Boolean {
        NodeRuntime.copyPackages(ctx)
        val usr = NodeRuntime.usrDir(ctx)
        val node = NodeRuntime.nodeBin(ctx)
        val npm = NodeRuntime.npmCliJs(ctx)
        if (!node.canExecute() || !npm.exists()) {
            onLine("[phone-agent] 运行时不完整，无法安装组件")
            return false
        }
        val pb = ProcessBuilder(
            node.absolutePath, npm.absolutePath,
            "install", "-g", "--prefix", usr.absolutePath,
            "file:${File(NodeRuntime.pkgDir(ctx), Versions.ZCODE_TGZ).absolutePath}",
            "file:${File(NodeRuntime.pkgDir(ctx), Versions.DSH_TGZ).absolutePath}",
        )
        pb.environment().putAll(NodeRuntime.environment(ctx))
        pb.redirectErrorStream(true)
        return try {
            val p = pb.start()
            p.inputStream.bufferedReader().forEachLine(onLine)
            val code = p.waitFor()
            if (code == 0) {
                File(usr, ".cli-installed-${Versions.ZCODE}-${Versions.DSH}").writeText(Versions.packagesMarker())
                true
            } else {
                onLine("[phone-agent] npm 退出码 $code")
                false
            }
        } catch (e: Exception) {
            onLine("[phone-agent] 安装异常：${e.message}")
            false
        }
    }
}
