package com.phoneagent

import android.content.Context
import java.io.File

/**
 * Generates the shell scripts that bridge the app to the embedded runtime.
 * Everything is exec'd through /system/bin/sh with absolute paths — no
 * /usr/bin/env shebang reliance, since the embedded prefix has no env(1).
 */
object SetupScripts {

    private fun scriptsDir(ctx: Context): File =
        File(ctx.filesDir, "scripts").apply { mkdirs() }

    private fun write(ctx: Context, name: String, body: String): File {
        val f = File(scriptsDir(ctx), name)
        f.writeText(body)
        f.setExecutable(true, false)
        return f
    }

    private fun cliInstallSnippet(): String {
        // double quotes required: single quotes would stop $PKG from expanding
        val pkgs = "\"file:${'$'}PKG/${Versions.ZCODE_TGZ}\" \"file:${'$'}PKG/${Versions.DSH_TGZ}\""
        return """
if [ ! -f "${'$'}MARKER" ]; then
  while true; do
    echo "[phone-agent] 首次运行：安装 zcode / dsh（需联网，约 1-3 分钟）..."
    if "${'$'}NODE" "${'$'}NPMCLI" install -g --prefix "${'$'}USR" $pkgs; then
      touch "${'$'}MARKER"
      echo "[phone-agent] 组件安装完成。"
      break
    fi
    echo "[phone-agent] 安装失败。请检查网络 / 设置中的 npm 镜像。"
    echo "[phone-agent] 输入 r 重试，输入 s 进入 shell 排查（exit 返回）。"
    read -r ans
    case "${'$'}ans" in
      s|S) /system/bin/sh ;;
    esac
  done
fi
""".trim()
    }

    /** Interactive bootstrap script: installs CLIs on first run, then exec's the zcode TUI. */
    fun zcodeScript(ctx: Context): File {
        val usr = NodeRuntime.usrDir(ctx).absolutePath
        val body = """
#!/system/bin/sh
# Phone-Agent: bootstrap then launch the zcode TUI.
USR='$usr'
PKG='${NodeRuntime.pkgDir(ctx).absolutePath}'
NODE="${'$'}USR/bin/node"
NPMCLI="${'$'}USR/lib/node_modules/npm/bin/npm-cli.js"
MARKER="${'$'}USR/.cli-installed-${Versions.ZCODE}-${Versions.DSH}"
NODE_PATH="${'$'}USR/lib/node_modules"
export NODE_PATH

${cliInstallSnippet()}

exec "${'$'}NODE" "${'$'}USR/lib/node_modules/zcode-app-cli/bin/zcode.js"
""".trim() + "\n"
        return write(ctx, "run-zcode.sh", body)
    }

    /** /system/bin/zcode wrapper installed by the Magisk module (root integration). */
    fun sysZcodeWrapper(): String {
        return """
#!/system/bin/sh
# Phone-Agent system-wide zcode (Magisk module phone_agent). Runs WITHOUT root.
PA="/system/etc/phone_agent/usr"
export LD_LIBRARY_PATH="${'$'}PA/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
export TMPDIR="${'$'}{TMPDIR:-/data/local/tmp}"
export HOME="${'$'}{ZCODE_HOME:-${'$'}HOME}"
case "${'$'}HOME" in ""|"/") HOME=/data/local/tmp ;; esac
mkdir -p "${'$'}HOME" 2>/dev/null
export TERM="${'$'}{TERM:-xterm-256color}"
export NODE_PATH="${'$'}PA/lib/node_modules"
exec "${'$'}PA/bin/node" "${'$'}PA/lib/node_modules/zcode-app-cli/bin/zcode.js" "${'$'}@"
""".trim() + "\n"
    }

    /** /system/bin/dsh wrapper installed by the Magisk module (root integration). */
    fun sysDshWrapper(): String {
        return """
#!/system/bin/sh
# Phone-Agent system-wide dsh (Magisk module phone_agent). Runs WITHOUT root.
PA="/system/etc/phone_agent/usr"
export LD_LIBRARY_PATH="${'$'}PA/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
export TMPDIR="${'$'}{TMPDIR:-/data/local/tmp}"
export HOME="${'$'}{DSH_HOME:-${'$'}HOME}"
case "${'$'}HOME" in ""|"/") HOME=/data/local/tmp ;; esac
mkdir -p "${'$'}HOME" 2>/dev/null
export NODE_PATH="${'$'}PA/lib/node_modules"
exec "${'$'}PA/bin/node" "${'$'}PA/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" "${'$'}@"
""".trim() + "\n"
    }
}
