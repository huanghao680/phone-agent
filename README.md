# Phone-Agent

把 [Zcode](https://github.com/zai-org/ZCode) 与 [DeepSeek Harness (`dsh`)](https://github.com/deepseek-ai/deepseek-harness) 装进安卓手机本机运行的**独立 APP**——不依赖 Termux 应用，root 可选。

> UI 采用 [Miuix](https://github.com/compose-miuix-ui/miuix)（HyperOS 风格 Compose 组件库，与 [InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived) 同款）+ Jetpack Compose。当前版本 **0.7.72**（versionCode 89）；组件版本：zcode **3.14.4-30**、dsh **0.2.0-rc.2**、opencode **1.18.34**、codex **0.160.0**、claude **2.1.287**。

四个桌面图标：

| 图标 | 形态 | 实现 |
|---|---|---|
| **Zcode** | 横屏全屏 Web UI | 官方 `packages/web` SPA + Hono 服务端（CI 构建），内嵌 Node 起 127.0.0.1:3030，WebView 呈现 |
| **DeepSeek Harness** | 横屏全屏 Web UI | 内嵌 Node 起 `dsh web`（127.0.0.1:3080），WebView 呈现 |
| **opencode** | 横屏全屏 Web UI | `opencode serve`（127.0.0.1:4096），Bun 单文件经 musl loader 启动，WebView 呈现 |
| **终端** | TUI 选择器 | 五选一：**zcode TUI / dsh TUI / Codex CLI / Claude Code / opencode**（Termux 终端模拟器 + 真实 PTY），页内还有**用量统计**与**设置**入口 |

外加**可选的 root 深度集成**：一键安装 Magisk 模块，把 `zcode` / `dsh` 命令装进 `/system/bin`，之后机内任意终端可执行，且运行时不需要 root。

## 架构

```
Phone-Agent.apk
├─ assets/runtime.tar.xz     Node 26.4.0 运行时（Termux 仓库的标准 Android ELF；仅为二进制来源）
│                            含 bash/coreutils/findutils/grep/sed/gawk/tar/curl/jq/ripgrep/git/python
│                            + proot（沙盒第二层）+ landlock-wrap（CI 用 NDK 编译）
├─ assets/runtime.manifest   运行时文件清单，用于删除「上一版有、本版没有」的旧文件
├─ assets/native/landlock-wrap + node-pty 的 android-arm64 预编译
├─ assets/packages/*.tgz     zcode-app-cli / @deepseek-ai/dsh 离线安装包
├─ assets/terminal-extra/    codex.tgz / claude.tgz / musl loader / 静态 rg
│
├─ [Zcode 图标]   ZcodeWebService  ──> node zcode-server ──> WebView(127.0.0.1:3030)
├─ [DSH 图标]     DshService(前台)   ──> node dsh web      ──> WebView(127.0.0.1:3080)
├─ [opencode 图标] OpencodeWebService ──> opencode serve ──> WebView(127.0.0.1:4096)
│                  spawn 顺序：root(su) → Shizuku(run-as，免 root) → 应用域直启
├─ [终端图标]     TerminalPicker ──> TerminalView(PTY) ──> zcode/dsh TUI、codex、claude、opencode
├─ [设置]         API Key（加密存储）/ npm 镜像 / HTTP 代理 / 工作区 / 存储诊断 / Agent 更新 / Shizuku / root 集成
└─ root 开关 ──> Magisk 模块 phone_agent（/system/bin/zcode、/system/bin/dsh + sepolicy.rule）
```

## 获取 APK（免本地构建）

本机无需安卓 SDK，用 GitHub Actions 云端构建：

1. 进入 **Actions → build → Run workflow**（push 到 main 自动触发）；
2. CI 依次：拉取运行时与依赖闭包 → NDK 交叉编译 node-pty → 编译 landlock-wrap → 打包 CLI tgz → 构建 ZCode Web SPA/Server → `assembleDebug`；
3. 在 Artifacts 下载 **Phone-Agent-debug-apk**，传到手机安装（允许未知来源）。

调试签名用仓库内提交的 `debug.keystore`，因此每次 CI 产物签名一致，`adb install -r` 可增量覆盖。

## 首次启动

**Zcode Web / DeepSeek Harness**：点图标 → 解压内嵌 Node 运行时（新版本首次会重解压并清理旧文件）→ 按需安装/升级组件（首次需联网，走设置里的 npm 镜像）→ 启动服务并加载页面。dsh 的 DeepSeek API Key 在**设置**页填写。

**终端**：选择要跑的 TUI。zcode TUI 内执行 `/login` 完成 OAuth；Codex/Claude 用各自的登录方式（凭证在应用私有目录，与官方一致）。

组件升级是**版本感知**的：安装脚本会比较 `package.json` 里的版本与 APK 内置版本，不一致才重装（早期版本"入口文件存在就跳过"导致打包升级永远不生效，已修）。

## opencode 的移植（Bun 单文件在 bionic 上的解法）

opencode 是 **Bun 单文件可执行文件**（185 MiB），官方只有 glibc/musl 两种 Linux 构建。在 Android 上直接跑会 `No such file or directory`——不是文件不存在，而是 ELF interpreter 找不到。解法是显式用 musl loader 启动，并补齐它链接的 GNU C++ 运行时：

```sh
ld-musl-aarch64.so.1 opencode      # + LD_LIBRARY_PATH 含 libstdc++.so.6 / libgcc_s.so.1
```

三者都取自 Alpine Linux v3.20 aarch64（`musl`、`libstdc++`、`libgcc`），与彼此同源，实测 `opencode --version` → `1.18.x`、TUI 完整渲染。

还有一个 Android 专属坑：**Bun 会在 `$TMPDIR` 下自解压**。Android 既没有 `/tmp`（根分区只读），继承来的 `/data/local/tmp` 又会 `EACCES`；启动脚本把 `TMPDIR` **钉死在 App 缓存目录**才起得来。

- 形态：终端选择器第五项 **opencode TUI**（真实 PTY），另有第四个桌面图标 **opencode webui**（`opencode serve`，127.0.0.1:4096）。

### opencode serve 的三种 spawn 模式（为什么需要 Shizuku）

Bun 进程由 App（应用域）直接 spawn 时会被 **Android 给应用进程安装的 seccomp 过滤器**立即杀死（`SIGSYS`，`Bad system call`）——实测排除了 SELinux（`setenforce 0` 后仍失败）、端口占用与环境影响；而同一二进制经 `su`（root 域）或 `run-as`（adbd/shell 域 fork 的进程不带 zygote 的 seccomp）都能正常 `listening`。因此 serve 的 spawn 顺序为：

1. **root**：`su -c`（环境变量以内联 export 传入，`su` 会净化调用方环境）；
2. **Shizuku**（免 root）：shell uid 经 `run-as` 回到应用数据目录——adbd/Shizuku fork 的进程不带该 seccomp 过滤器；此路径需 debug 构建（`run-as` 要求）；
3. **应用域直启**：保留作为占位，失败时明确提示 seccomp 限制与非 root 替代方案。

已知边界：`run-as` 只改 uid 不改 SELinux 域（shell 域对应用数据**可读不可写**），因此 root 模式运行过的残留文件会让 Shizuku 路径预检失败并给出明确指引；serve 的就绪探测优先读 Shizuku 的 `/proc/net/tcp` 端口表（Android 11+ 对 App 隐藏该文件）。

## Android 适配层（本项目的主要工作之一）

上游假设 glibc Linux，在 bionic 上有一系列硬伤。安装/更新 dsh 后，补丁器（`assets/scripts/patch-dsh-android.mjs`，Node 实现、精确字符串锚点、幂等、锚点丢失只告警不破坏文件）会逐项修复：

| 问题 | 现象 | 修法 |
|---|---|---|
| `flock` 原生模块无安卓构建 | 启动即抛 `flock is not supported on android-arm64` | 替换为 no-op stub（单进程写本就串行） |
| `link()` 被 SELinux 拒绝（Android 11+ 应用域） | 会话日志写入 EACCES | 包成 `link → rename` 回退 |
| `dsh-sandbox-local` 的 `PLATFORM_CHAINS` 无 `android` 键 | 非 danger 会话 bash 直接 fail-closed（**连探针都不跑**） | 补 `android: ["bwrap","landlock"]`（两元素，保留探针语义） |
| `dsh-attachment-local` 对祖先目录 fsync | `read_image` 报 `EACCES open /data/user/0` | 容忍 EACCES/EPERM（平台自有层级不归 App 担保） |
| `@vscode/ripgrep` 按 `process.platform` 找平台包 | `glob`/`grep` 全废（android 平台包不存在） | 安装 shim 包，指向**静态** ripgrep（Termux 的 `rg` 或 `@vscode/ripgrep-linux-arm64`；**codex 自带的那份是动态链接的，不能用**），并在装完后实际执行 `rg --version` 验证 |
| 新增原生模块 `node-addon-require-builtin` | 0.1.7 起启动失败（无安卓构建且不发源码） | 按其语义用 JS 兜底（`requireBuiltin` = `require`），配合 `bin/dsh` 包装脚本强制 `--expose-internals`（该 flag 不允许走 `NODE_OPTIONS`） |
| `sharp` 无 android-arm64 二进制 | `read_image` 报 `Could not load the sharp module…` | 在 **sharp 自己的 node_modules** 里装 `@img/sharp-wasm32@<同版本>`（wasm 版 libvips）；该步骤随补丁器执行，自更新后不会丢 |
| 脚本 shebang 写死 Termux 前缀 | `npm`/`npx`/`dsh`/`pkg` 报 `bad interpreter` | 批量改写为应用前缀（`$USR`） |
| git 的 shell 路径编译期写死 Termux 前缀 | `git clone` 报 `cannot exec 'git-upload-pack'… unable to fork` | 二进制内原地替换该字符串（`SHELL`/`GIT_SHELL_PATH` 均无效） |
| git 的 libcurl 不认 `CURL_CA_BUNDLE` | HTTPS 克隆报 `error adding trust anchors` | 注入 `GIT_CONFIG_COUNT/_KEY_0=http.sslCAInfo/_VALUE_0=<cert.pem>` |

其余环境注入：`OPENSSL_CONF`、`CURL_CA_BUNDLE`、`SSL_CERT_FILE`、`TMPDIR`、`GIT_EXEC_PATH`、`GIT_TEMPLATE_DIR`、`SHELL`、代理相关变量（Node 不读安卓系统 WiFi 代理）、以及下面沙盒的 `DSH_PERMISSION_MODE`。

## dsh 沙盒（可在 Android 上真正启用）

上游 `dsh-sandbox-local` 在 Linux 上按 `bwrap → landlock` 选后端；Android 上两者都没有（无用户命名空间、老内核无 Landlock），所以默认只会 fail-closed。本项目在 `$USR/bin` 放了一个 **bwrap 兼容 shim**，让 dsh 的探测与调用原样落上来，内部按能力分层：

| 层 | 后端 | 适用 |
|---|---|---|
| 1 | `landlock-wrap`（内核强制，免 root） | 内核 ≥ 5.13 的设备；探针失败自动跳过 |
| 2 | `proot`（ptrace 路径改写，用户态） | 任意内核、免 root（乐视/小米平板 4 走这层） |
| 3 | 都不可用 → 以 `bwrap: ` 前缀失败 | dsh fail-closed；此时回落 `danger-full-access` 不回归 |

实测（乐视内核 3.18，proot 层，真实 dsh headless 会话内）：

```
workspace 内写入   → 允许（持久）
workspace 外写入   → 拒绝（Permission denied；真实文件系统无残留）
/sdcard、/storage  → 不可见
/tmp               → 沙盒内映射到 $TMPDIR/tmp，可写
系统目录           → 只读可见（可执行）
```

设计要点：**proot 的骨架根目录会被冻结**（chmod a-w），否则越界写入会在临时骨架里"静默成功"、退出即毁——agent 会误以为写成功了；冻结后变成真实 EACCES，正好命中 dsh 的拒绝语义。骨架在子进程退出后自清理，并处理 SIGTERM/INT（`job_kill` 不会留下残留目录）。shim 从自身路径推导前缀并**自己导出 `PATH`/`LD_LIBRARY_PATH`**——dsh 的工具沙盒会用净化过的环境启动它，否则连 `mkdir` 都链接不了。

已知边界（诚实说明）：

- proot 层是**用户态的路径改写**，强度不等同内核 LSM；沙盒语义与上游一致，**只管文件写入**，不管网络与进程可见性。
- `read-only` 模式在 proot 层下**看不见工作区**（真 bwrap 是全盘只读可见）；这是 fail-closed 的取舍，默认的 `workspace-write` 不受影响。
- 沙盒模式由 `$USR/share/phone-agent/sandbox-mode` 驱动 `DSH_PERMISSION_MODE`；dsh 的 Web GUI 会话自身预设 `danger-full-access`（上游行为）。

## 存储模型（实测）

| 路径 | 读写 | 执行 |
|---|---|---|
| 应用私有目录（`filesDir`，agent 的 `$HOME`） | ✅ | ✅（targetSdk 28 的关键取舍） |
| `/sdcard`、`/storage/emulated/0`、`/Android/data/<pkg>` | ✅ | ❌ FUSE noexec |

所以工作区可以放在共享存储（配合「所有文件访问」权限），但**agent 本体与工具链必须留在应用私有目录**。设置页有**存储诊断**：由 App 自己进程写一份 `cache/storage-diag.txt`（存储隔离按 uid 的挂载命名空间生效，`adb shell run-as` 测出来的结论与 App 自身不同，必须在 App 进程内测）。

## root 深度集成（可选）

> 全部为可选增强，三个图标的常规使用**完全不依赖 root**。

在**设置**页：

- **安装系统级命令**：把 Node 运行时复制为 Magisk 模块 `phone_agent`，在 `/system/bin` 暴露 `zcode` / `dsh` 包装脚本（**重启后生效**）。模块同时写入 `sepolicy.rule`，放行应用域 `link()`（zcode 原子写配置需要）并实时生效。
- **修复幻象进程限制**：放开 Android 12L+ 对后台子进程的清理（系统 OTA 后可能需重跑）。
- **卸载系统级命令**：删除模块，重启后失效。

## 用量统计

终端页的**用量统计**同时读取两个来源：

- **Zcode**：本机 SQLite `model_usage` 表（近 14 天按天、按模型汇总输入/输出/推理 tokens）。
- **DSH**：会话日志 `~/.dsh/sessions/<工作区>/<会话>/session.v3.jsonl.zstd`。它是**多帧 zstd 拼接**文件，用 `zstd-jni` 连续流解码；用量取自 `assistant/message` 事件的 `data.usage`（输入/输出/缓存命中/推理）与 `data.message.source`（模型名）。

## 故障排查

- **终端一闪而过 / exec 报错**：仅支持 arm64（`uname -m` 应为 `aarch64`）。
- **组件安装失败**：检查网络；必须走代理的网络在设置里填 **HTTP 代理**（Node 不读安卓系统 WiFi 代理，直连会全量超时）；必要时换 npm 镜像。
- **`zcode` 报 EACCES（写配置失败）**：Android 11+ SELinux 禁应用域 `link()`；非 root 走 `rename` 预置，root 用户可点"安装系统级命令"让模块放行。
- **后台会话被杀**：root 用户执行"修复幻象进程限制"；同时给 App 关闭电池优化。
- **dsh 打不开**：设置页填 DeepSeek API Key；看 `cache/dsh.log`（App 私有目录）。
- **Claude Code 报 `EAI_AGAIN` / 无法连接 api.anthropic.com**：Codex/Claude 的 TUI 会话继承设置里的 HTTP 代理变量；在需要代理的网络（国内直连不了 Anthropic/OpenAI）务必先填代理再启动。Claude 启动即做连通性检查，Codex 到登录/调用时才会暴露。
- **`read_image` 或 `glob`/`grep` 失效**：多半是 agent 自更新把补丁冲掉了——重开一次 App（或跑一次设置页的更新）会重跑补丁器；日志里会有 `[phone-agent] …patched` 记录。
- **通知不显示**：Android 13+ 在系统设置里手动允许通知（不影响功能）。

## Agent 更新的 A/B 槽位

zcode / dsh 的更新与 Android 系统更新同一套契约（真机验证过切换与回滚）：

- 包装在 `files/agents/<pkg>/{a,b}` 两个槽位，用 `usr/lib/node_modules/<pkg>` 符号链接指向现役槽——入口路径、启动脚本、补丁器、包装器全都不变。
- **更新装入备用槽，下次启动切换**；切换后的那次启动是“验证启动”，失败则**自动回滚**到上一版本并重试一次。
- 设置页的 Agent 更新区有 **回滚** 按钮（有备用版本时才出现）。
- **手动更新不会被降级**：APK 内置版本只在「现役版本缺失或更旧」时才重装，手动更新到更新版本后不会被钉回内置版。
- **Agent 无法破坏机制**：工作区与 `~/.dsh/` 会种入 `AGENTS.md`（两个 CLI 都自动读入上下文），写明环境事实与更新政策（禁止 `npm i -g` 之类全局安装）；启动时还有**槽位完整性校验**——若现役树版本与记录不符（会话内被改动），会采用实际版本并重打 Android 补丁。
- **更新检查走内嵌 Node**：registry 查询与 tgz 下载都优先经内嵌 Node（代理变量已在会话环境注入）；`HttpURLConnection` 在 https-over-CONNECT 代理下会超时（真机实测 5 个包里 4 个失败而 Node 全部成功），Java 路径仅作回退。binary agent（codex/claude/opencode）查 `/latest` 而非锁定版本，否则打包升级永远查不到新版本。
- **APK 驱动升级会回写实际版本**：自动升级装入现役槽后把真实版本写回 `slot.json`——否则 `activeNeedsPinned` 每次启动都会误判"更旧"而反复重装。

## Shizuku 集成（免 root 的 adb 权限，实测可用）

[Shizuku](https://github.com/RikkaApps/Shizuku) 把 adb 级（shell uid）的 binder 暴露给普通应用：用户安装 Shizuku 管理器并启动其服务（Android 11+ 可用无线调试、或连电脑 adb、root 设备可直接 root 启动；**每次重启后需重新启动**）。本项目用它做两类事：

1. **opencode webui 的免 root 启动**（见上文三种 spawn 模式）；
2. **一组 shell 权限工具**（设置页 Shizuku 卡片，逐条真机验证）：

| 工具 | 为什么 App 自己做不到 | 实现 |
|---|---|---|
| 修复幻象进程限制 | 需要 `WRITE_SECURE_SETTINGS` | `device_config put` + `settings put` |
| 授权「所有文件访问」 | `appops` 只有 shell 能改别的 uid | `appops set --uid <uid> MANAGE_EXTERNAL_STORAGE allow`（授权前后状态都会显示） |
| 查看监听端口 | Android 11+ 对 App 隐藏 `/proc/net`，App 只能盲探测 | 读 `/proc/net/tcp` 生成带用途标注的端口表（如 `4096 [opencode webui]`） |
| 加入电池优化白名单 | App 无法把自己加进 Doze 白名单；不加则前台服务与 agent 会话后台被杀 | `dumpsys deviceidle whitelist +<pkg>` |
| 授予通知权限 | Android 13+ 运行时权限，被拒时服务失败完全不可见 | `pm grant POST_NOTIFICATIONS` |

边界（Android 13+ 实测，shell uid 也做不了）：特权端口绑定（<1024 `EACCES`）、读取其他应用的私有数据、静默安装 APK（`INSTALL_PACKAGES` 虽在 shell 权限表里但安装框架仍拦截）、纯 shell 域访问应用私有目录（SELinux 拦截，所以必须 `run-as`）。

## dsh 插件系统（第三方插件实测可用）

dsh 的 Web GUI 自带 **插件** 管理页（侧边栏 → 插件），由 `dsh-plugin-manager` 在 profile 目录里跑 `pnpm view/add` 完成检索、安装、启用与回滚。本移植为其补齐了两个前提：

- **pnpm**：插件管理器的外部依赖。固定使用 **pnpm 10（纯 JS）**——pnpm 12 的原生可执行文件采用 flock 存储锁，在目标文件系统上直接 `ERR_PNPM_STORE_DIR_ACQUIRE_OPERATION_LOCK: lock_shared() not supported`。App 启动时自动装好（离线时下次启动重试）。
- **registry 计划**：管理器自带 npmjs → npmmirror 的回退链，与设置的 npm 镜像无关（腾讯镜像会被当作"私有源"单独询问）。

真机验证（dsh 0.1.7-rc.2，乐视）：

| 插件 | 类型 | 结果 |
|---|---|---|
| `dsh-balance-widget@0.6.2` | 余额监控（dsh 0.2.x） | ✅ 侧边栏底部卡片：实时余额 + 当日消耗（服务端复用注入的 `DEEPSEEK_API_KEY`） |
| `dsh-theme-studio@0.6.3` | 外观/主题 | ✅ 设置页出现「主题工作室」：12 组主题预设（Forest/Monochrome…）、强调色自定义 |
| `dsh-balance-plugin@0.2.2`（0.1.7 专用） | 余额监控 | 0.2.0 起被 dsh 的兼容性评估器自动跳过（peer 声明 `dsh 0.1.7`），换用上面的 widget 即可 |

> 插件兼容性由 dsh 自身的评估器把关：peer 依赖与运行时不匹配的 bundle 会被**跳过并在插件页标注错误**（fail-closed，不拖垮 GUI）。选插件时看它的 `peerDependencies`。

插件管理工具（`plugin_manager`）默认只在 Web GUI 的 Creator 模式启用；CLI 会话如需用工具安装，在 profile 的 `cordis.patch.yml` 加 `- id: tool-plugin-manager \n name: '@deepseek-ai/dsh-plugin-manager/tools' \n disabled: false`，或直接用低层命令 `dsh plugin --profile <name> add <pkg>`（转发 pnpm，装完需自行把 bundle 名写进 profile `package.json` 的 `dsh.profile.bundles`）。

## 已知限制

- **129 个 ELF 二进制**把 Termux 前缀编译进了 `.rodata`（无法安全做文本替换）；核心命令（bash/node/npm/git/python/curl/jq/rg）都不受影响。
- **`/tmp` 不存在**（Android 根分区只读）；非沙盒环境请用 `$TMPDIR`，沙盒内 `/tmp` 可用。
- **zcode 的 WebFetch 工具在本环境不可用**：两个 URL 都卡满 60s 工具上限，而同一 URL 用 Node `fetch` 3 秒即返回 200——其客户端是 bundle 内的自定义实现，属上游缺陷；替代做法是用 bash + node 抓取。
- **`pkg` 命令能跑但没有后端 `apt`**（未打包），需要包管理时用 `npm`。
- **opencode 的 App 域直启被 seccomp 拦截**：Bun 需要的系统调用在应用进程的 seccomp 白名单外（`SIGSYS`/`Bad system call`），因此 webui 在非 root 设备上需要 **Shizuku**（见上文），或 root 设备的 `su` 路径；opencode TUI 不受影响（终端选择器第五项）。
- **16KB 页对齐**：`libandroidx.graphics.path.so`（来自 compose 1.8-beta 一线）未对齐，Android 15+ 会有调试警告，不影响运行；`libtermux.so` 已处理。

## 开发者

- **构建**：Linux/WSL 下 `bash scripts/fetch-runtime.sh && bash scripts/fetch-packages.sh && bash scripts/fetch-terminal-extra.sh && bash scripts/fetch-zcode-web.sh`，然后 `gradle :app:assembleDebug`（JDK 17 + Android SDK + NDK）。Windows 上 assets 由 CI 产出。
- **版本双锁**：`scripts/versions.env` 与 `app/src/main/java/com/phoneagent/Versions.kt` 必须同步；每次迭代同时升 `versionName`/`versionCode`。
- **targetSdk 28 是刻意的**：Android 10+ 在 targetSdk ≥ 29 时禁止从应用私有目录 exec，内嵌 Node 必须能在 filesDir 执行（Termux 同款取舍）；本 App 侧载分发，不受商店政策约束。
- **运行时清单**：`scripts/fetch-runtime.sh` 会把 `usr/bin`、`usr/lib` 顶层、`usr/libexec` 的文件列表写进 `runtime.manifest`；App 解压后据此删除上一版才有的文件（否则被移出清单的包会留下"旧二进制 + 新库"的组合，curl 曾因此报 `cannot locate symbol SSL_get_ex_new_index`）。
- **webUI 缩放**：Chromium WebView 的 zoom-out 下限是「布局适配刻度」，`minimum-scale` 压不下去；注入 JS 把布局视口设为屏幕宽度的 2 倍（`initial-scale=0.5`），因此默认 0.5x、可捏合放大到 8x。

## 同类项目与借鉴

调研过的先行项目（2026-09）：

- [slopus/happy](https://github.com/slopus/happy)（23.9k⭐）— Claude Code/Codex 移动客户端，**遥控器流派**：agent 跑在电脑上，手机经自建 relay + E2EE 远程操控。UX 参考：会话跨设备恢复、完成通知、语音。
- [siteboon/claudecodeui（CloudCLI）](https://github.com/siteboon/claudecodeui)（13.8k⭐）— 本机 Node 服务 + node-pty 网页终端 + 文件管理器的移动适配 Web UI，支持多 CLI；本项目 Zcode Web GUI 的实现蓝图。
- [opcode（原 Claudia）](https://github.com/opcode-ws/opcode) — Tauri 桌面 GUI，功能设计参考（用量统计、checkpoint）。
- [Magisk-Modules-Alt-Repo/node](https://github.com/Magisk-Modules-Alt-Repo/node) — 与本项目 root 集成同构的先例（`system/usr/share/node` + `/system/bin` 包装脚本）；本项目用动态链接的 Termux Node，wrapper 内自带 `LD_LIBRARY_PATH`。
- [JaneaSystems/nodejs-mobile](https://github.com/JaneaSystems/nodejs-mobile) — 早期内嵌 Node 方案，已停更（Node 18，不满足 zcode 的 ≥22.19）。

结论：**"把 agent CLI 本体装进 APK 在手机本机运行"目前没有现成完整先例**；本项目的各组成部分均有成熟参考并被独立验证。

## 真机验证状态

| 设备 | 系统 | Zcode Web | dsh Web | 终端 TUI | opencode Web | Shizuku 工具 | root 集成 |
|---|---|---|---|---|---|---|---|
| 乐视 Le Max 2 | LineageOS 18.1（Android 11）+ KernelSU | ✅ | ✅ | ✅ zcode/dsh/codex/claude/opencode | ✅（root 域） | ✅ | ✅ |
| 小米平板 4 | Android 17 + Magisk | ✅ | ✅（A/B 从 0.1.5-rc.3 跨大版本升到 0.2.0-rc.2，插件页正常） | ✅ zcode/dsh/codex/claude/opencode | ✅（**Shizuku 免 root 路径**） | ✅ 五条全部实测 | ✅ |
| vivo V2048A | Android 13，**无 root** | ✅ | ✅ | ✅（`rename` 预置配置，无需 root） | 需 Shizuku（未测） | 未测 | 不适用 |
| Redmi K60 | Android 16 / 澎湃 OS3 | ✅ | ✅ | ✅ | 需 Shizuku（未测） | 未测 | 未测 |
| 小米平板 6 Pro | Android 15 / 澎湃 OS3 | ✅ | ✅ | ✅ | 需 Shizuku（未测） | 未测 | 未测 |

沙盒与工具链的完整自测由 dsh / zcode 两个 CLI 在设备上自行跑出报告（`selftest/` 目录），逐项含命令与原始输出。

最近一轮全量自测（v0.7.72，2026-10-04，小米平板 4 + 乐视）：

- **A/B 槽位**：legacy 直装布局（zcode 3.14.3-28 / dsh 0.1.5-rc.3）自动迁移到双槽并跨大版本升级到 zcode 3.14.4-30 / dsh 0.2.0-rc.2，升级后 zcode CLI、dsh web（含插件页 8 个官方插件）全部正常；
- **失败自动回滚**：手工构造损坏的备用槽后启动，`beginBoot` 切换 → 验证失败 → 自动回滚并重试成功，槽位回到 verified；
- **opencode**：Shizuku 路径 webui HTTP 200 + 界面完整渲染（非 root），TUI 完整渲染；
- **Shizuku 工具**：幻象进程修复 / 全文件访问授权 / 端口表 / 电池白名单 / 通知权限，五条逐一执行并用 adb shell 独立核实状态；
- **zcode web**：升级后 3030 正常出欢迎页（HTTP 200）。

## 许可证

本项目 Apache-2.0。第三方组件与归属见 [NOTICE.md](NOTICE.md)（Termux 终端库 Apache-2.0、Node.js/npm、zcode-app-cli、@deepseek-ai/dsh、以及运行期内置的 curl/git/python/jq/ripgrep/proot 等）。
