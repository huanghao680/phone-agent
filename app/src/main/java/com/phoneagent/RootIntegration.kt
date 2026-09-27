package com.phoneagent

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * Optional root deep-integration. Installs a Magisk module that exposes
 * system-wide `zcode` / `dsh` commands backed by the embedded Node runtime, so
 * ANY terminal on the device (Termux, adb shell, MT manager, …) can launch the
 * TUI. The installed commands run as the invoking user — no root at runtime.
 */
object RootIntegration {

    const val MODULE_ID = "phone_agent"
    /** Absolute module path; also shown by the settings UI. */
    const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"

    fun hasRoot(): Boolean = Shell.isAppGrantedRoot() == true

    fun installModule(ctx: Context): List<String> {
        if (!hasRoot()) return listOf("未获得 root 授权（请在 root 管理器中允许 Phone-Agent）")
        if (!NodeRuntime.isRuntimeExtracted(ctx)) NodeRuntime.extractRuntime(ctx)

        val staging = File(ctx.filesDir, "module").apply { deleteRecursively() }
        val sysBin = File(staging, "system/bin").apply { mkdirs() }
        File(staging, "module.prop").writeText(
            """
            id=$MODULE_ID
            name=Phone-Agent Runtime
            version=v${BuildConfig.VERSION_NAME}
            versionCode=${BuildConfig.VERSION_CODE}
            author=Phone-Agent app
            description=System-wide zcode / dsh commands (embedded Node.js runtime). Installed by the Phone-Agent app; the commands themselves need no root.
            """.trimIndent() + "\n"
        )
        File(sysBin, "zcode").writeText(SetupScripts.sysZcodeWrapper())
        File(sysBin, "dsh").writeText(SetupScripts.sysDshWrapper())
        // Android 11+ SELinux blocks app-domain hard links; zcode's config writer
        // uses link() atomically, so re-allow it for our domains (persisted by the
        // module's sepolicy.rule, applied live below).
        val seRules = listOf(
            "allow untrusted_app_27 app_data_file file link",
            "allow untrusted_app_27 app_data_file lnk_file create",
            "allow untrusted_app_27 app_data_file lnk_file getattr",
            "allow untrusted_app app_data_file file link",
            "allow untrusted_app app_data_file lnk_file create",
            "allow runas_app app_data_file file link",
            "allow runas_app app_data_file lnk_file create",
        )
        File(staging, "sepolicy.rule").writeText(seRules.joinToString("\n") + "\n")

        val usr = NodeRuntime.usrDir(ctx).absolutePath
        val stg = staging.absolutePath
        val cmds = arrayOf(
            "rm -rf $MODULE_DIR/system/etc/phone_agent/usr",
            "mkdir -p $MODULE_DIR/system/bin $MODULE_DIR/system/etc/phone_agent",
            "cp -a $stg/module.prop $MODULE_DIR/module.prop",
            "cp -a $stg/system/bin/zcode $stg/system/bin/dsh $MODULE_DIR/system/bin/",
            "cp -a $usr $MODULE_DIR/system/etc/phone_agent/usr",
            "chmod 755 $MODULE_DIR/system/bin/zcode $MODULE_DIR/system/bin/dsh",
            "chmod 644 $MODULE_DIR/module.prop",
            "chmod -R 755 $MODULE_DIR/system/etc/phone_agent/usr/bin",
            "chcon -R u:object_r:system_file:s0 $MODULE_DIR/system 2>/dev/null || true",
            "rm -f $MODULE_DIR/disable $MODULE_DIR/remove",
        )
        val log = mutableListOf<String>()
        var allOk = true
        for (c in cmds) {
            val r = Shell.cmd(c).exec()
            if (!r.isSuccess) allOk = false
            log += if (r.isSuccess) "[ok] $c" else "[fail] $c :: " + r.err.joinToString(" ")
        }
        // live-apply the link rules so no reboot is needed for zcode's config writes
        var seOk = true
        for (rule in seRules) {
            val r = Shell.cmd("ksud sepolicy patch \"$rule\" 2>/dev/null || magiskpolicy --live \"$rule\" 2>/dev/null").exec()
            if (!r.isSuccess) seOk = false
        }
        log += if (seOk) "[ok] SELinux link 规则已实时应用（重启后由模块 sepolicy.rule 续用）"
        else "[warn] SELinux 规则实时应用失败——重启后由模块自动应用；期间 zcode 写配置可能仍报 EACCES"
        log += if (allOk)
            "安装完成。重启手机后，任意终端输入 zcode / dsh 即可使用（无需 root 运行）。"
        else "安装过程中有步骤失败，请查看上方日志。"
        return log
    }

    fun uninstallModule(): List<String> {
        if (!hasRoot()) return listOf("未获得 root 授权")
        val r = Shell.cmd("rm -rf $MODULE_DIR").exec()
        return if (r.isSuccess)
            listOf("已卸载。重启后系统级命令失效。")
        else listOf("卸载失败：", *r.err.toTypedArray())
    }

    /** Stops Android 12L+ from killing our background node processes ("phantom processes"). */
    fun fixPhantomProcesses(): List<String> {
        if (!hasRoot()) return listOf("未获得 root 授权")
        val out = mutableListOf<String>()
        for (c in arrayOf(
            "device_config put activity_manager max_phantom_processes 2147483647",
            "settings put global settings_enable_monitor_phantom_procs false",
        )) {
            val r = Shell.cmd(c).exec()
            out += if (r.isSuccess) "[ok] $c" else "[fail] $c :: " + r.err.joinToString(" ")
        }
        out += "完成。该设置在系统 OTA / 重启后可能需要重新执行。"
        return out
    }
}
