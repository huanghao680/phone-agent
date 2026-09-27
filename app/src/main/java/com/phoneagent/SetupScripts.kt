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
        return """
install_pkg() {
  TGZ="file:${'$'}PKG/${'$'}1"
  MARKER="${'$'}USR/${'$'}2"
  EXTRA="${'$'}3"
  NAME="${'$'}4"
  if [ -f "${'$'}MARKER" ]; then return 0; fi
  while true; do
    echo "[phone-agent] 安装 ${'$'}NAME（需联网）..."
    if "${'$'}NODE" "${'$'}NPMCLI" install -g --prefix "${'$'}USR" ${'$'}EXTRA "${'$'}TGZ"; then
      touch "${'$'}MARKER"
      echo "[phone-agent] ${'$'}NAME 安装完成。"
      return 0
    fi
    echo "[phone-agent] ${'$'}NAME 安装失败。检查网络 / 镜像 / 设置中的 HTTP 代理。"
    echo "[phone-agent] 输入 r 重试，输入 s 进入 shell 排查（exit 返回），输入 q 跳过。"
    read -r ans
    case "${'$'}ans" in
      r|R) ;;
      s|S) /system/bin/sh ;;
      q|Q) return 1 ;;
    esac
  done
}

# node-pty 需要安卓原生绑定：dsh 用 --ignore-scripts 安装，随后注入 CI 预编译产物
install_pkg "${Versions.ZCODE_TGZ}" ".cli-installed-zcode" "" "zcode TUI"
install_pkg "${Versions.DSH_TGZ}" ".cli-installed-dsh" "--ignore-scripts" "DeepSeek Harness"

if [ -f "${'$'}PKG/node-pty-prebuild/pty.node" ] && [ -d "${'$'}USR/lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty" ]; then
  PTY_DIR="${'$'}USR/lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty/prebuilds/android-arm64"
  mkdir -p "${'$'}PTY_DIR"
  cp "${'$'}PKG/node-pty-prebuild/pty.node" "${'$'}PTY_DIR/"
  echo "[phone-agent] 已注入 node-pty 安卓预编译。"
fi

# sharp 没有 android-arm64 官方二进制；装 wasm32 通用构建（版本跟随 dsh 里的 sharp）
if [ -d "${'$'}USR/lib/node_modules/@deepseek-ai/dsh/node_modules/sharp" ]; then
  SHARP_VER=$("${'$'}NODE" -p "require('${'$'}USR/lib/node_modules/@deepseek-ai/dsh/node_modules/sharp/package.json').version")
  if ! "${'$'}NODE" -e "require.resolve('@img/sharp-wasm32/package.json',{paths:['${'$'}USR/lib/node_modules/@deepseek-ai/dsh/node_modules/sharp']})" 2>/dev/null; then
    echo "[phone-agent] 安装 @img/sharp-wasm32@${'$'}SHARP_VER（sharp 安卓替代）..."
    "${'$'}NODE" "${'$'}NPMCLI" install -g --prefix "${'$'}USR" --ignore-scripts "@img/sharp-wasm32@${'$'}SHARP_VER" || echo "[phone-agent] sharp-wasm32 安装失败，dsh 图片功能将不可用。"
  fi
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
NODE_PATH="${'$'}USR/lib/node_modules"
export NODE_PATH

${cliInstallSnippet()}

if [ ! -f "${'$'}USR/lib/node_modules/zcode-app-cli/bin/zcode.js" ]; then
  echo "[phone-agent] zcode 未安装成功，进入 shell 以便排查（exit 退出）。"
  exec /system/bin/sh
fi

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
