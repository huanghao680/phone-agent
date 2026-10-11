# Phone-Agent 交接文档

> 最后更新：2026-10-11 · 当前版本 v0.8.9（versionCode 126）· CI 绿 · 仓库 github.com/huanghao680/phone-agent（main）
>
> **v0.8.9 两台真机回归均已完成**：clover（root——a11y ref 三端点、⇄切换器往返、NUL 自愈投毒实测、会话→TUI、release 签名构建实测、Codex+Claude 网关真实对话闭环）+ vivo（无 root——0.8.9 装机、三 web 引擎 200/401/200、zcode TUI 与 dsh-tui 四步引导渲染、**Shizuku 全路径端点 /ports /ui-dump /screenshot /shell**、a11y ref 点击、会话→TUI 实测；⇄ 切换器未在 vivo 复测——横屏 LazyColumn 导航不稳，该项已在 clover 闭环）。
> **vivo 踩到的真雷（教训再次验证「装新设备一律用 CI 产物」）**：vivo 装的是**本地构建 126**（无 CI staging 资产），且这台从未打开过 zcode web → `files/zcode-server`、`zcode-web` 缺失，zcode web 无限重启「服务端缺失」；dsh-tui profile 也从未自举（够不到 npm registry）→ TUI 报 Cannot find module。本轮经 **clover→PC→vivo 的 USB tar 搬运**救活（zcode-web 资产 52MB 包 + dsh-tui profile + 会话样本；当时代理只有 ~44KB/s，500MB CI 包下不动——**换网络直装 CI 产物才是正路，首启自举会自然补齐**）。另记：`opencode run`（CLI 非交互）在 clover 上挂于 models.dev 抓取（见 roadmap 2 登记），与本雷无关。**vivo 现跑 = 本地构建代码 + 移植资产**，纯净 CI 首启验证仍欠一次。无障碍边界：原生 Compose 字段 set/get 往返可靠，**WebView 内字段抓不全**（长文本截断、text=null）。

本文档面向接手继续开发的开发者。读完这一篇 + `README.md`，你应当能独立完成构建、发布和继续迭代。

---

## 1. 这个项目是什么

一个独立 Android APK，在**手机本机**运行五个 AI 编码 CLI（无 Termux、root 可选）：

| Agent | 形态 | 接入方式 |
|---|---|---|
| zcode | Web UI (127.0.0.1:3030) + TUI | 内嵌 Node + npm 安装 |
| DeepSeek Harness (dsh) | Web UI (3080) + CLI | 内嵌 Node + npm 安装 |
| opencode | Web UI (4096) + TUI | Bun 单文件 ELF + musl loader |
| Codex CLI | TUI only | 静态 musl 二进制 |
| Claude Code | TUI only | musl 动态二进制 + 自带 loader |

架构：APK 内嵌 Termux 系 Node 运行时（xz 快照），A/B 槽位管理 agent 版本，Jetpack Compose + Miuix 做 UI，前台服务保活，Shizuku 提供非 root 的 adb 权限通道。

**五 agent 在两类设备上全部验证可用**：root 机（小米平板 4 / Magisk、乐视 Le Max 2 / KernelSU）与无 root 机（vivo V2048A / Android 13）。

## 2. 快速上手

### 构建

```bash
# 本机（有 SDK）：Windows + Git Bash
C:/Users/HH0113/.gradle/wrapper/dists/gradle-9.6.1-all/ea0s6ybcs323rkwxzj29dsh0/gradle-9.6.1/bin/gradle assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

**⚠️ 本地构建的 APK 没有 runtime.tar.xz 等运行时资产，装到全新设备首启必崩**（已改为明确报错）。**发给用户 / 装新设备一律用 CI 产物**：push 到 main 自动构建，artifact 名 `Phone-Agent-debug-apk`（约 500MB）。

**版本号**：`app/build.gradle.kts` 的 versionCode/versionName。**不要用 sed 改**——本环境 sed 对该文件静默失败（0.8.1→0.8.6 期间四个提交全发成 0.8.1），用 python 或直接编辑。

### 发布

push tag `v*` 触发 `.github/workflows/release.yml` → 构建并挂 GitHub Release（需要 `permissions: contents: write`，已配好）。首个正式 release：v0.7.94。

### CI

- `build.yml`：push main 触发，~6-7 分钟，产 debug APK + staged-assets
- `release.yml`：push tag 触发
- 单元测试：`gradle testDebugUnitTest`（13 个，全绿）——含 versions 双锁测试（`scripts/versions.env` 与 `Versions.kt` 漂移即失败）

### 网络

- git push / GitHub 大文件下载需代理 `http://192.168.1.197:7890`（`git -c http.proxy=... push`；curl 加 `--proxy`）
- gh CLI 偶发 TLS 超时，重试或换 curl
- WebFetch 对 GitHub 不稳，用 curl

### 测试设备

| 设备 | 系统 | 权限 | 状态 |
|---|---|---|---|
| 小米平板 4 (clover) | Android 17 | Magisk root | 全功能验证机，**Shizuku 已授权** |
| 乐视 Le Max 2 | LineageOS 18.1 | KernelSU | 旧测试机（近期 USB 不稳），系统有崩溃弹窗循环 |
| vivo V2048A | Android 13 | **无 root** | 无 root 全链路验证机 |

vivo 特性（踩过的坑）：adb install 弹确认框 **17 秒超时自动拒**（装包必须有人盯屏幕点允许）；同版本重装被拒；无 curl（探测用 node fetch + 显式 LD_LIBRARY_PATH）；`settings put` 启用无障碍时灵时不灵。

## 3. 核心模块地图（app/src/main/java/com/phoneagent/）

### 运行时与安装
- `NodeRuntime.kt` — 运行时解压（assets/runtime.tar.xz）、usr/bin 工具（pa-* wrapper、pnpm wrapper）、AGENTS.md 种子、sandbox 工具。**exec-bit 自愈清单**在这里（ld-musl/rg/libstdc++/libgcc/pnpm）
- `Bootstrap.kt` — zcode/dsh 的 npm 安装 + `applyAndroidPatches`（跑 assets/scripts/patch-dsh-android.mjs，全部 Android 兼容补丁）
- `AgentSlots.kt` — A/B 槽位（files/agents/<pkg>/{a,b} + slot.json + usr symlink）；`compareVersions` 是防降级守卫（有单测）
- `Versions.kt` — 版本双锁之一（另一个 scripts/versions.env），**必须同步改**

### 服务与引擎
- `ZcodeWebService.kt` / `DshService.kt` / `OpencodeWebService.kt` — 三个前台服务，各管一个 web 引擎；**boot 有 booting 标志去重**（restoreAll + Activity 双启动会并发）
- `Watchdog.kt` — 四态探活保活（HEALTHY/DEGRADED_HTTP/DEGRADED_LOG/DEAD），5s 一拍 + 指数退避 + 熔断；**阶段打点在 `dsh-boot` logcat tag**
- `WatchdogTargets.kt` — 从 AgentRegistry 生成探活目标
- `EngineState.kt` + `BootReceiver.kt` — 开机恢复（默认关，设置页开关）
- `PhoneToolService.kt` — **127.0.0.1:8899 工具通道**（agent 用 bash/curl 调用）：/screenshot /ui-dump /a11y-dump /a11y-click /a11y-set-text /a11y-text /browser-* /tap /input /key /shell /clipboard /ports /device /vdisplay-*。**/usr/bin/pa-* wrapper** 让 agent 免记端点

### 会话管理
- `SessionData.kt` — 三家数据源统一读取（zcode SQLite / dsh jsonl.zstd 双根含 sessions-tui / opencode SQLite）；`AgentSession.fromTui` 标终端来源
- `SessionTrash.kt` — 回收站（删除前先备份：SQLite 用 ATTACH+CTAS，dsh 搬目录）
- `SessionMigrate.kt` — 跨 agent 会话迁移（Transcript 中间表示，3 提取器 + 3 构造器）
- `UsageActivity.kt` — 用量统计 + 会话管理 UI（搜索、TUI 徽标、删除走回收站）

### 新功能层
- `AgentRegistry.kt` — 五 agent 的单一注册表（选择器卡片、看门狗目标都由它生成；**加第 6 个 agent 改这一处**）
- `FileIncomingActivity.kt` — 分享/打开方式直达会话（files/incoming 临时工作区，7 天 TTL）
- `AttachmentBridge.kt` — WebView 文件选择器（三个 web Activity + 浏览器工作台共用）
- `BrowserWorkbenchActivity.kt` — agent 用的隔离浏览器（/browser-open|snapshot|close，含 console 遥测）
- `A11yService.kt` — 无障碍语义树（/a11y-dump 的 ref+diff 模式，借 agent-device 设计；0.8.9 加 `clickByRef`（不可点时自动爬祖先 3 层）/ `setTextByRef`（CJK 安全写入，绕开 `input text` 不支持非 ASCII）/ `textByRef`（读字段实值，落实"别信 rc"）；`lastNodes` 每次 dump 重建）
- `DiagActivity.kt` — 诊断页（看门狗状态 + 端口表 + 日志尾部）
- `ApkUpdate.kt` — APK 手动更新检查（GitHub latest）
- `LanProvider.kt` — **第三方网关写入器**（见 §5）

### UI
- `SettingsActivity.kt` — 设置页（凭据网络 / 组件更新 / 界面缩放 / 工作区 / **第三方模型网关** / 引擎保活诊断 / Shizuku / root / 维护）
- `TerminalPickerActivity.kt` — 五卡选择器（从 AgentRegistry 生成）
- `ZcodeTerminalActivity.kt` — 共享 PTY 终端宿主（zcode/dsh TUI，**多会话并存**：SessionHolder.nodeSessions per-agent map）

## 4. 第三方网关（workbuddy2api）——最近完成的大块

用户的局域网网关（与代理同机 192.168.1.197）：
- **:3002 端口** = 新版，三协议全支持：`/v1/chat/completions`、`/v1/messages`（Anthropic，x-api-key）、`/v1/responses`（OpenAI Responses，Bearer）
- **:7863 端口** = 旧部署，只有 chat completions

设置页「第三方模型网关」开关写入三份**各 CLI 原生配置**（`LanProvider.apply`）：
- Codex → `~/.codex/config.toml`：`wire_api="responses"`（**0.160.0 已弃用 "chat"，写 chat 直接拒启**）+ `env_key="WORKBUDDY_API_KEY"`
- Claude → `~/.claude/settings.json` env 块：ANTHROPIC_BASE_URL/AUTH_TOKEN/MODEL/**MAX_TOKENS=8192**（**推理模型 hy4 的 thinking 会吃光默认额度返回空内容**）
- opencode → `~/.config/opencode/opencode.jsonc`：provider 块，apiKey 用 `{env:WORKBUDDY_API_KEY}` 插值

Token 存 `~/.workbuddy-key`（600），由 `SetupScripts.workbuddyEnvSnippet()` 在 TUI 启动时 export（用内嵌 node 解析 settings.json）。**token 不入仓库、不入 assets、不入 CLI 配置文件**。

已实测：vivo 上 Claude Code 完成 onboarding 并真实对话（hy4-preview）。Codex 配置已写好待人工跑一次确认。

## 5. 已知事实与深坑（省你几轮调试）

**平台层**：
- Android 的 SQLiteDatabase 默认关外键（FK 级联不生效，删除需显式清子表）；**ATTACH 无法创建新文件**（先 createNewFile）
- **Shizuku shell（uid 2000）无权写应用私有目录**——需要 shell 侧回传结果时用 stdout 尾部标记（如 `echo rc=$?`），临时文件方案必死
- Android `input text` 不支持非 ASCII（CJK 要走剪贴板 + KEYCODE_PASTE；**注入的 Ctrl+V 和弦 rc=0 但被 Miuix 输入框忽略**——别信 rc，要 dump 字段实值）
- Android 内置 org.json 的 **JSONArray 不是 Iterable**（按索引遍历）
- `evaluateJavascript` 回调是 JSON 字面量（先 JSONTokener 解一层）
- Termux 系 node 编译期路径指向 /data/data/com.termux：**TMPDIR 必须显式设置**；`/usr/bin/env` 在子进程无法链接 → **shebang 直接指向真 node**
- dsh web 无 token 返回 401 —— 探活要把**任何 HTTP 响应**当 alive

**dsh 0.2.x 特有**：
- CLI 表面只有 web/headless/sdk/acp（无终端 TUI）；TUI 来自社区插件 @deepseek-harness-tui/dsh-tui（独立启动器 + 自有 profile `dsh-tui`，首次运行自举；必须设 `DSH_TUI_SESSION_ROOT` 隔离会话存储）
- `@deepseek-ai/*` npm dist-tags：latest=0.0.1-rc.1 假包、真版本在 next → **pnpm 解析 peer 会失败**，让 dsh-tui 自己自举而非 plugin add
- pnpm 10.34.6（12.x 的 flock 在目标 FS 不可用）；**spawn pnpm 需要 wrapper 自给自足**（LD_LIBRARY_PATH+TMPDIR，判据 `env -i pnpm --version` 能跑）
- 插件兼容性评估器 fail-closed：peer 不匹配直接拒装

**本仓库工程坑**：
- **sed/python heredoc 编辑在本环境多次静默失败或写坏文件**（patcher 正则就是被 python 补丁写坏的）——改完必 grep 验证 + `node --check` + 全新安装验证
- **旧设备旧树掩盖新装问题**：patcher 坏了小米/乐视照常工作（老补丁还在），只有全新安装才炸——**改安装/补丁逻辑必须找一台全新安装验证**
- LazyColumn 虚拟化：排序沉底的列表项不出现在 uiautomator dump——用搜索框验证
- uiautomator dump 对长 Compose 文本节点抓不全；弹窗按钮坐标用 `rfind`
- UI 遥控（input tap/keyevent）在 onboarding 流程上时序不可靠——让用户手点

**上游事实**：
- dsh 上游（deepseek-ai/deepseek-harness）不做 Android 平台，**不要尝试上游化**
- zcode 上游 monorepo tag 停在 v3.14.3，web bundle 的 ref 不能动

## 6. 验证工具箱

- **多设备真机**：vivo（3048005132007QN，无 root）+ 小米（38d4fdf，root+Shizuku）；乐视 USB 不稳
- **UI 自动化**：uiautomator dump + 坐标点击（python 脚本在 C:/Users/HH0113/AppData/Local/Temp/oc/ 有大量可复用样例：导航、hold-to-confirm 模拟、内存采样）
- **长按模拟**：`input swipe x y x+1 y+1 2600`（**1px 偏移**，同点 swipe 会被立即合成抬手）
- **工具通道自测**：`adb shell curl -s http://127.0.0.1:8899/<endpoint>`（有 curl 的设备）或 run-as node fetch
- **诊断**：设置 → 诊断（聚合页）；logcat tag：`dsh-boot`（启动阶段）、`phone-agent-watchdog`、`agent-slots`、`phone-tool`
- **dsh-tui doctor**：`node <profile>/node_modules/@deepseek-harness-tui/dsh-tui/bin/dsh-tui.js doctor`
- **杀引擎测自愈**：run-as kill dsh 的 node 进程，看门狗 5s 内判定 DEAD 并自动拉起

## 7. Roadmap（按优先级）

1. ~~TUI 会话管理深化~~ **✅ 0.8.9**：会话列表每张卡加「⌨ 打开对应 TUI」按钮、详情页加同款卡片（zcode/dsh→共享 PTY 宿主，opencode→独立宿主；映射与终端选择器一致）
2. **Claude Code 真实对话复核**：**✅ 0.8.9 复核完成（clover）**——Codex `exec`（responses 通道，RC=0，答"1+1 等于 2"）与 Claude `-p`（messages 通道，RC=0）经 workbuddy 网关真实对话全部通过。三个实测发现：
   - Claude 拒绝以 root 跑 `--dangerously-skip-permissions`（"cannot be used with root/sudo"）——复核脚本必须以 **app uid** 执行（工具通道 /shell 正好是 app uid，天然合适）；
   - `~/.workbuddy-key` 与 `~/.codex/config.toml` 在 clover 上出现**全 NUL 字节损坏**（长度正常、内容 0x00——写入未落盘的老崩溃残留），Codex 报 `config.toml:1:197: key with no value` 拒启。**0.8.9 加了冷启动自愈 `LanProvider.healCorrupted`**：文本格式文件里出现任何 NUL 即移为 `*.nul-corrupt`，CLI 回到干净默认态（token() 也防御性读空）。
   - **未决：`opencode run`（非交互 CLI）挂起**——日志停在 `Failed to fetch models.dev cause=TimeoutError` 后无限等待；`--pure`、显式 `--model`、`OPENCODE_DISABLE_MODELS_FETCH`、DNS 代理在/不在均如此。**App 的两个发布面不受影响**：`opencode serve`（web 4096 正常）和裸 `opencode` TUI。这是 opencode CLI 自身在 Bun+Android 上的问题，非网关配置错误；若要支持 agent 会话里跑 `opencode run`，需继续查（候选：strace 看它 timeout 后卡在哪个 syscall）。
3. **附件桥在 dsh 的落地**：**上游已就位（本轮 vivo 实测发现）**——dsh web（0.2.0-rc.2）插件清单已含 `dsh-client-ui-attachment` + `dsh-client-file-upload`，界面上有「添加文件或调用指令」按钮；桥侧 `AttachmentBridge.attach` 本就挂在 DshActivity 的 WebView 上。**未闭环**：经 a11y ref 点击与坐标点击该按钮都没唤起系统文件选择器（疑似组合菜单需二次交互，或 WebView onShowFileChooser 未被触发）——下轮先人工点一次定性，再判定是桥的缺陷还是上游交互形态
4. ~~无障碍通道扩展~~ **✅ 0.8.9**：`/a11y-click?ref=@eN&long=true`（ref 级点击，不可点时爬祖先 3 层，跨应用可用）、`/a11y-set-text?ref=@eN&text=...`（**CJK 安全写入输入框**——绕开 `input text` 不支持非 ASCII 和和弦粘贴不可靠两大坑）、`/a11y-text?ref=@eN`（读回字段实值，落实「别信 rc」）。refs 每次 dump 后刷新
5. ~~多会话 UI~~ **✅ 0.8.9**：终端宿主右上角 **⇄ 切换器**（跨 node/binary 会话列出全部活跃 TUI 一键跳转）；ZcodeTerminalActivity 补了 `onNewIntent`（singleTask 下选择器/切换器换 agent 不再被吞）
6. **armv7 / x86_64**：0.8.9 加了不支持 ABI 的**明确警告横幅**（选择器顶部，非 arm64-v8a 直接说明）；完整支持是资产管线大工程——fetch-runtime/fetch-native/fetch-terminal-extra 产出的 node 快照、node-pty prebuild、Bun ELF、codex/claude 二进制全部 arm64-only，每 ABI 都要重建 + 装包验证。按用户需求排期
7. ~~release 签名~~ **✅ 0.8.9**：gradle 读 `PA_RELEASE_STORE / PA_RELEASE_STORE_PASS / PA_RELEASE_KEY_ALIAS / PA_RELEASE_KEY_PASS`，keystore 在场则 release 签名，否则回退 debug（CI 无密钥也能构建）；release.yml 增加解码 secrets 步骤（`PA_KS_BASE64 / PA_KS_PASS / PA_KEY_PASS`）。本地 keystore 在 `release.keystore`（gitignored，别名 phone-agent，密码在 `release-key.pass`）——**换成正式签名后旧 debug 签名的 release 无法原地升级，全新安装即可**

## 8. 关键设计决策（勿轻易推翻）

- **不依赖 Termux 应用**：内嵌 Termux 系 Node 快照，targetSdk 28（W^X 需要）
- **官方 web 原样覆盖**，不自研 UI（DeepCode 的 fork 负担教训）
- **A/B 槽位 + 补丁器**管理 agent 版本，agent 会话内禁止自行更新（AGENTS.md 种入政策）
- **只写各 CLI 自己的配置格式**（网关接入），App 只管开关和 token 文件——CLI 改配置格式时我们不用跟着改
- **token 不入仓库/assets/CLI 配置**，只存设备本地文件
- **所有删除走回收站**（先备份后删除，备份失败拒绝删除）
- **第三方网关三协议各走各的原生端点**（Codex=responses、Claude=messages、opencode=chat），不做统一转换层

## 9. 联系与资源

- 对标项目：dsh-mobile-apk（DeepCode，634★）、Mobile-Harness（522★）、agent-device（4918★，工具面设计范本）
- dsh-TUI 插件：github.com/ccch1mneyyy/dsh-TUI（4.2k★，官方首推）
- dsh 上游：github.com/deepseek-ai/deepseek-harness（"Everything is a Plugin"）
- 用户群/QQ 群在 dsh-mobile-apk README 有链接（可摸用户反馈）
