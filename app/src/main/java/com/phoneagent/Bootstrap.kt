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

        val zcodeOk = npmInstall(ctx, node, npm, usr, Versions.ZCODE_TGZ, ignoreScripts = false, onLine = onLine)
        if (zcodeOk) marker(ctx, "zcode").writeText("ok")
        val dshOk = npmInstall(ctx, node, npm, usr, Versions.DSH_TGZ, ignoreScripts = true, onLine = onLine)
        if (dshOk) marker(ctx, "dsh").writeText("ok")
        if (dshOk) {
            injectPtyPrebuild(ctx, onLine)
            installSharpWasm(ctx, node, npm, usr, onLine)
        }
        return dshOk // the dsh service only needs dsh; zcode is for the terminal
    }

    private fun marker(ctx: Context, name: String): File =
        File(NodeRuntime.usrDir(ctx), ".cli-installed-$name")

    private fun npmInstall(
        ctx: Context,
        node: File,
        npm: File,
        usr: File,
        tgz: String,
        ignoreScripts: Boolean,
        onLine: (String) -> Unit,
    ): Boolean {
        if (File(usr, tgzToNodeModulesPath(tgz)).exists()) return true
        val args = mutableListOf(
            node.absolutePath, npm.absolutePath,
            "install", "-g", "--prefix", usr.absolutePath,
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
            code == 0
        } catch (e: Exception) {
            onLine("[phone-agent] 安装异常：${e.message}")
            false
        }
    }

    private fun tgzToNodeModulesPath(tgz: String): String = when (tgz) {
        Versions.ZCODE_TGZ -> "lib/node_modules/zcode-app-cli/package.json"
        else -> "lib/node_modules/@deepseek-ai/dsh/package.json"
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
     * sharp publishes no android-arm64 binary; install the wasm32 universal
     * build at the exact matching version so dsh's image features can load.
     */
    private fun installSharpWasm(ctx: Context, node: File, npm: File, usr: File, onLine: (String) -> Unit) {
        val sharpPkg = File(usr, "lib/node_modules/@deepseek-ai/dsh/node_modules/sharp/package.json")
        if (!sharpPkg.exists()) return
        try {
            val ver = org.json.JSONObject(sharpPkg.readText()).getString("version")
            val probe = ProcessBuilder(
                node.absolutePath, "-e",
                "require.resolve('@img/sharp-wasm32/package.json',{paths:['${sharpPkg.parentFile}']})",
            ).start()
            if (probe.waitFor() == 0) return // already present
            onLine("[phone-agent] 安装 @img/sharp-wasm32@$ver ...")
            val pb = ProcessBuilder(
                node.absolutePath, npm.absolutePath,
                "install", "-g", "--prefix", usr.absolutePath,
                "--ignore-scripts", "@img/sharp-wasm32@$ver",
            )
            pb.environment().putAll(NodeRuntime.environment(ctx))
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.bufferedReader().forEachLine(onLine)
            p.waitFor()
        } catch (e: Exception) {
            onLine("[phone-agent] sharp-wasm32 安装失败（dsh 图片功能受限）：${e.message}")
        }
    }
}
