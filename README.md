# Phone-Agent

把 [Zcode](https://github.com/zai-org/ZCode) 和 [DeepSeek Harness (`dsh`)](https://github.com/deepseek-ai/deepseek-harness) 两个开源 AI Agent 移植到安卓（arm64、建议 Android 8+、已 root 更佳）的**独立 APP**——不依赖 Termux 应用。

一个 APK，两个桌面图标：

| 图标 | 形态 | 实现 |
|---|---|---|
| **Zcode** | 全屏终端 TUI | 内嵌 Node.js 运行时 + Termux 终端模拟器（Apache-2.0 源码），真实 PTY 里跑 `zcode` 交互式 TUI |
| **DeepSeek Harness** | 全屏 Web UI | 内嵌 Node 起 `dsh web`（127.0.0.1:3080），WebView 全屏呈现 |

外加**可选的 root 深度集成**：一键安装 Magisk 模块，把 `zcode` / `dsh` 命令装进 `/system/bin`——之后机内**任意终端**（Termux、adb shell、MT 管理器终端…）输入 `zcode` 即可打开 TUI，且**运行时不需要 root**。

## 架构

```
Phone-Agent.apk
├─ assets/runtime.tar.xz     Node 26.4.0 运行时（取自 Termux 软件仓库的标准 Android ELF，
│                            MIT 协议；仅为二进制来源，与 Termux 应用无关）
├─ assets/packages/*.tgz     zcode-app-cli / @deepseek-ai/dsh 离线安装包
│
├─ [Zcode 图标]      TerminalView(PTY) ── setup.sh ──> node bin/zcode.js（首次自动 npm 安装）
├─ [DSH 图标]        DshService(前台) ──> node dsh web ──> WebView(127.0.0.1:3080)
├─ [设置]            API Key / npm 镜像 / root 集成开关
└─ root 开关 ──> Magisk 模块 phone_agent
      /system/bin/zcode、/system/bin/dsh（包装脚本，任意终端可执行）
      /system/etc/phone_agent/usr/**（Node 运行时）
```

## 获取 APK（免本地构建）

本机无需安卓 SDK，用 GitHub Actions 云端构建：

1. 把本仓库推送到 GitHub；
2. 进入 **Actions → build → Run workflow**（push 到 main 也会自动触发）；
3. 构建完成后在 Artifacts 下载 **Phone-Agent-debug-apk**；
4. 传到手机安装（需允许安装未知来源应用）。

## 首次启动

**Zcode（TUI）**：点开图标 → 自动解压内嵌 Node 运行时 → 终端里自动安装 zcode/dsh 组件（首次需联网，走设置里的 npm 镜像，默认 npmmirror；zcode/dsh 本体已离线内置）→ 进入 zcode TUI → 在 TUI 内执行 `/login` 完成 Z.AI OAuth 登录。

**DeepSeek Harness**：点开图标 → 后台完成解压/安装后自动启动 `dsh web` → APP 从服务输出捕获一次性令牌 URL 并加载 → 全屏显示 Web UI（约需 1–2 分钟，首次更久）。DeepSeek API Key 在 **设置** 页填写，也可在 dsh 页面的 API Key 向导里填。

## root 深度集成（可选）

> 本节全部为**可选**增强。APP 的两个图标（Zcode TUI / DeepSeek Harness Web UI）**完全不依赖 root**——它们使用的 Node 运行时内置于 APK、解压在应用私有目录；Magisk 模块只负责把 `zcode` / `dsh` 命令暴露给 APP 之外的终端。非 root 时 dsh 后台服务可能被系统"幻象进程清理"回收（回到 APP 会自动拉起，可关电池优化缓解；root 修复开关可根治）。

在 **设置** 页：

- **安装系统级命令**：把 Node 运行时复制为 Magisk 模块 `phone_agent`（/data/adb/modules/），在 `/system/bin` 暴露 `zcode` / `dsh` 包装脚本。**重启手机后生效**，之后任意终端：

  ```sh
  zcode            # 打开 zcode TUI（无需 root 运行）
  dsh web          # 启动 DeepSeek Harness
  ```

  提示：系统命令使用终端各自的 `HOME`（可用 `ZCODE_HOME` / `DSH_HOME` 覆盖），与 APP 内的会话配置相互独立。
- **修复后台幻象进程限制**：Android 12L+ 会清理后台子进程导致终端会话被杀，此开关放宽该限制（系统 OTA 后可能需重跑）。
- **卸载系统级命令**：删除模块，重启后失效。

## 故障排查

- **终端一闪而过 / exec 报错**：仅支持 arm64 设备（`uname -m` 应为 `aarch64`）。
- **组件安装失败**：检查网络；若手机处在"必须走代理"的网络，在设置里填 **HTTP 代理**（Node 不读安卓系统 WiFi 代理，直连会全量超时）；必要时换 npm 镜像。
- **`zcode` 报 EACCES（写配置失败）**：Android 11+ SELinux 禁止应用域硬链接，而 zcode 用 link() 原子写配置。root 用户点设置的"安装系统级命令"，模块会写入 `sepolicy.rule` 并实时放行（重启后自动续用）。
- **后台会话被杀**：root 用户执行"修复幻象进程限制"；同时给 APP 关闭电池优化。
- **dsh 打不开**：设置页填 DeepSeek API Key；看 `Android/data/com.phoneagent/cache/dsh.log`。
- **通知不显示**：Android 13+ 在系统设置里手动允许通知（不影响功能）。

## 开发者

- **本地构建**：Linux/WSL 下 `bash scripts/fetch-runtime.sh && bash scripts/fetch-packages.sh`，然后 `gradle :app:assembleDebug`（需 JDK 17 + Android SDK）。Windows 上 assets 由 CI 产出。
- **版本升级**：改 `scripts/versions.env` 与 `app/src/main/java/com/phoneagent/Versions.kt`（两者必须同步），重跑脚本 + 构建。
- **targetSdk 28 是刻意的**：Android 10+ 在 targetSdk ≥ 29 时禁止从应用私有目录 exec，内嵌 Node 必须放在 filesDir 执行（Termux 同款取舍）；本 APP 侧载分发，不受商店政策约束。
- **后续路线（Zcode Web GUI）**：官方 monorepo 的 `packages/web`（React/Vite）+ `packages/zcode-server-cli`（Hono）可像 dsh 一样以 WebView 承载；CI 从锁定 ref 构建 `@zcode/web` dist + server bundle 即可。注意 server-cli 带守护进程/服务注册等桌面假设，需要适配，故暂列 v1.5。

## 同类项目与借鉴

调研过的先行项目（2026-09）：

- [slopus/happy](https://github.com/slopus/happy)（23.9k⭐）— Claude Code/Codex 移动客户端，**遥控器流派**：agent 跑在电脑上（`npm i -g happy`），手机经自建 relay + E2EE 远程操控。UX 参考：会话跨设备恢复、完成通知、语音。
- [siteboon/claudecodeui（CloudCLI）](https://github.com/siteboon/claudecodeui)（13.8k⭐）— 本机 Node 服务 + node-pty 网页终端 + 文件管理器的移动适配 Web UI，支持多 CLI。**Zcode Web GUI（v1.5）的直接实现蓝图**；注意 node-pty 是 native 模块，安卓上需 NDK 交叉编译。
- [opcode（原 Claudia）](https://github.com/opcode-ws/opcode) — Tauri 桌面 GUI，功能设计参考（用量统计、checkpoint 管理）。zcode 的 JSONL 用量日志已有现成解析器（better-ccusage）。
- [Magisk-Modules-Alt-Repo/node](https://github.com/Magisk-Modules-Alt-Repo/node) / DerGoot 的 Systemless Node.js — **与本项目 root 集成完全同构的先例**（`system/usr/share/node` + `/system/bin` 包装脚本）。其包装脚本极简是因为用静态官方 Node；本项目用 Termux 动态链接 Node，wrapper 内设 `LD_LIBRARY_PATH` 是必要且正确的。该项目依赖 Systemless Mkshrc 伴随模块，本项目的 wrapper 自带环境变量，无此依赖。
- [JaneaSystems/nodejs-mobile](https://github.com/JaneaSystems/nodejs-mobile) — 早期内嵌 Node 方案，已停更（Node 18，无法满足 zcode 的 ≥22.19）；验证了本项目"打包 Termux 仓库现代 Node 二进制"的路线选择。
- [MatthewJamisonJS/claude-on-the-go](https://github.com/MatthewJamisonJS/claude-on-the-go) — 早期 WebSocket 桥接方案，同为遥控器流派。

结论：**"把 agent CLI 本体装进 APK 在手机本机运行"目前没有现成完整先例**，本项目的各组成部分（Termux 二进制重打包、PTY 终端、WebView 承载 Web UI、Magisk 系统级命令）均有成熟参考并已被独立验证。

## 真机验证状态

已在两台设备实测通过（2026-09-27/28，构建 2c3546f+）：

| 设备 | 系统 | Zcode TUI | DeepSeek Harness Web UI | root 系统级命令 |
|---|---|---|---|---|
| 乐视 Le Max 2 | LineageOS 18.1（Android 11）+ KernelSU | ✅ 完整渲染（v3.14.3-28 标题、模型配置向导、软键盘可输入） | ✅ 完整加载（内测声明→API Key 向导，可点击交互） | ✅ `/system/bin/zcode` 非 root 执行成功 |
| 小米平板 4 | Android 17 + Magisk | ✅（同款 TUI） | ✅ Web UI 加载正常 | 未测（同为 Magisk，逻辑一致） |

已知 Android 15+ 提示：`libtermux.so` 未做 16KB 页对齐会弹"不受支持"调试警告，**不影响运行**（release 版可通过 linker flags 消除）。

## 待办

吸收自同类项目调研的路线图（详见上一节"同类项目与借鉴"）：

**体验增强（低成本优先）**

- [ ] **会话结束本地通知**（借鉴 happy）：zcode TUI 会话退出时发系统通知，点击回到 APP / 重开会话。落点：`ZcodeTerminalActivity` 的 `onSessionFinished` 回调，复用 `Notifications` 通道。
- [ ] **会话跨重启恢复**（借鉴 happy）：APP 进程被杀后重新打开图标能接回上一次 zcode 会话（当前 `SessionHolder` 只能扛 Activity 重建，扛不住进程死亡）。落点：zcode CLI 原生支持 `--resume <sessionId>` / `-c`，把 sessionId 持久化到 SharedPreferences，启动脚本按需带参重启即可。
- [ ] **用量统计页**（opcode 风格）：读取 zcode 的本地 JSONL 会话日志做模型用量/花费统计（社区已有解析先例 better-ccusage）。

**里程碑 v1.5：Zcode Web GUI**

- [ ] 优先走 dsh 已验证的同构路径：CI 从锁定 ref 构建官方 monorepo 的 `packages/web`（Vite 静态产物）+ `packages/zcode-server-cli`（Hono 服务端），APP 内嵌 Node 运行 + WebView 承载，零 root。
- [ ] 备选路线：照 CloudCLI 架构自建（node-pty + WebSocket 网页终端）——`node-pty` 为 native 模块，需在 CI 用 NDK 交叉编译，工程量更大，仅在官方 server-cli 的守护进程假设无法安卓化时启用。

## 许可证

本项目 Apache-2.0。第三方组件见 [NOTICE.md](NOTICE.md)（Termux 终端库 Apache-2.0；Node.js/npm MIT/ISC；zcode-app-cli MIT；@deepseek-ai/dsh MIT）。
