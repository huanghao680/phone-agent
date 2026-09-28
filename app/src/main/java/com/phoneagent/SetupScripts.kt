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

# sharp 没有 android-arm64 官方二进制；它的加载器只在 sharp 自己的 node_modules
# 里找 @img/sharp-wasm32，必须装在本地依赖树中
SHARP_DIR="${'$'}USR/lib/node_modules/@deepseek-ai/dsh/node_modules/sharp"
if [ -d "${'$'}SHARP_DIR" ] && [ ! -d "${'$'}SHARP_DIR/node_modules/@img/sharp-wasm32" ]; then
  SHARP_VER=$("${'$'}NODE" -p "require('${'$'}SHARP_DIR/package.json').version")
  echo "[phone-agent] 安装 @img/sharp-wasm32@${'$'}SHARP_VER（sharp 安卓替代）..."
  (cd "${'$'}SHARP_DIR" && "${'$'}NODE" "${'$'}NPMCLI" install --prefix "${'$'}SHARP_DIR" --no-save --no-package-lock --ignore-scripts "@img/sharp-wasm32@${'$'}SHARP_VER") || echo "[phone-agent] sharp-wasm32 安装失败，dsh 图片功能受限。"
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

WORKDIR="${'$'}{PHONE_AGENT_WORKSPACE:-}"
RESUME="${'$'}{PHONE_AGENT_RESUME:-1}"   # 1 = continue latest session when one exists
RESUME_FLAG=""
if [ "${'$'}RESUME" = "1" ] && [ -d "${'$'}HOME/.zcode" ] && ls "${'$'}HOME"/.zcode/*/rollout >/dev/null 2>&1; then
  RESUME_FLAG="-c"
fi

if [ -n "${'$'}WORKDIR" ] && [ -d "${'$'}WORKDIR" ]; then
  echo "[phone-agent] 工作区：${'$'}WORKDIR"
  exec "${'$'}NODE" "${'$'}USR/lib/node_modules/zcode-app-cli/bin/zcode.js" ${'$'}RESUME_FLAG --cwd "${'$'}WORKDIR"
fi

exec "${'$'}NODE" "${'$'}USR/lib/node_modules/zcode-app-cli/bin/zcode.js" ${'$'}RESUME_FLAG
""".trim() + "\n"
        return write(ctx, "run-zcode.sh", body)
    }

    /** Interactive dsh TUI bootstrap (Installs then exec's the dsh TUI). */
    fun dshTuiScript(ctx: Context): File {
        val usr = NodeRuntime.usrDir(ctx).absolutePath
        val body = """
#!/system/bin/sh
# Phone-Agent: bootstrap then launch the dsh TUI.
USR='$usr'
PKG='${NodeRuntime.pkgDir(ctx).absolutePath}'
NODE="${'$'}USR/bin/node"
NPMCLI="${'$'}USR/lib/node_modules/npm/bin/npm-cli.js"
NODE_PATH="${'$'}USR/lib/node_modules"
export NODE_PATH

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

install_pkg "${Versions.ZCODE_TGZ}" ".cli-installed-zcode" "" "zcode TUI"
install_pkg "${Versions.DSH_TGZ}" ".cli-installed-dsh" "--ignore-scripts" "DeepSeek Harness"

if [ -f "${'$'}PKG/node-pty-prebuild/pty.node" ] && [ -d "${'$'}USR/lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty" ]; then
  PTY_DIR="${'$'}USR/lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty/prebuilds/android-arm64"
  mkdir -p "${'$'}PTY_DIR"
  cp "${'$'}PKG/node-pty-prebuild/pty.node" "${'$'}PTY_DIR/"
  echo "[phone-agent] 已注入 node-pty 安卓预编译。"
fi

if [ ! -f "${'$'}USR/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" ]; then
  echo "[phone-agent] dsh 未安装成功，进入 shell 以便排查（exit 退出）。"
  exec /system/bin/sh
fi

exec "${'$'}NODE" "${'$'}USR/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
""".trim() + "\n"
        return write(ctx, "run-dsh-tui.sh", body)
    }

    /** Interactive codex TUI bootstrap (extracts binary from assets, then exec's it). */
    fun codexScript(ctx: Context): File {
        val usr = NodeRuntime.usrDir(ctx).absolutePath
        val body = """
#!/system/bin/sh
# Phone-Agent: launch the Codex CLI TUI (static musl binary + vendor tree).
USR='$usr'
PKG='${NodeRuntime.pkgDir(ctx).absolutePath}'
BIN="${'$'}PKG/vendor/aarch64-unknown-linux-musl/bin/codex"

if [ ! -x "${'$'}BIN" ]; then
  echo "[phone-agent] codex 二进制缺失（当前构建未包含）。"
  echo "[phone-agent] 按回车退出。"
  read -r dummy
  exit 1
fi

WORKDIR="${'$'}{PHONE_AGENT_WORKSPACE:-}"
if [ -n "${'$'}WORKDIR" ] && [ -d "${'$'}WORKDIR" ]; then
  echo "[phone-agent] 工作区：${'$'}WORKDIR"
  cd "${'$'}WORKDIR"
fi

# PATH includes the bundled ripgrep/sandbox companions; --no-daemon because the
# bare extraction is not a complete npm package layout
export PATH="${'$'}PKG/vendor/aarch64-unknown-linux-musl/codex-path:${'$'}PATH"
exec "${'$'}BIN" --no-daemon --dangerously-bypass-approvals-and-sandbox
""".trim() + "\n"
        return write(ctx, "run-codex.sh", body)
    }

    /** Interactive claude TUI bootstrap (launches via the bundled musl loader). */
    fun claudeScript(ctx: Context): File {
        val usr = NodeRuntime.usrDir(ctx).absolutePath
        val body = """
#!/system/bin/sh
# Phone-Agent: launch Claude Code TUI via the bundled musl loader.
USR='$usr'
PKG='${NodeRuntime.pkgDir(ctx).absolutePath}'
LOADER="${'$'}PKG/ld-musl-aarch64.so.1"
BIN="${'$'}PKG/claude"

if [ ! -x "${'$'}LOADER" ] || [ ! -x "${'$'}BIN" ]; then
  echo "[phone-agent] claude 二进制缺失（当前构建未包含）。"
  echo "[phone-agent] 按回车退出。"
  read -r dummy
  exit 1
fi

WORKDIR="${'$'}{PHONE_AGENT_WORKSPACE:-}"
if [ -n "${'$'}WORKDIR" ] && [ -d "${'$'}WORKDIR" ]; then
  echo "[phone-agent] 工作区：${'$'}WORKDIR"
  cd "${'$'}WORKDIR"
fi

export HOME="${'$'}{HOME:-${'$'}USR/../home}"
exec "${'$'}LOADER" "${'$'}BIN"
""".trim() + "\n"
        return write(ctx, "run-claude.sh", body)
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
