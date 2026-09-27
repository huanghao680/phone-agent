# Phone-Agent：DeepSeek Harness + Zcode 安卓独立移植方案

## 目标

把两个 Node.js 生态的开源 CLI 移植到已 root 的安卓手机，做成**完全独立、不依赖 Termux 应用**的 APK：

1. **Zcode TUI** —— APP 内嵌终端模拟器 + 内嵌 Node 运行时，点开图标即进入 zcode 交互式 TUI；
2. **DeepSeek Harness (`dsh`)** —— 独立图标，前台服务启动 `dsh web`（127.0.0.1:3080），全屏 WebView 呈现其 Web UI；
3. **机内任意终端可用（root 深度集成，可选开关）** —— 一键生成并安装 Magisk 模块，把 `zcode` / `dsh` 包装脚本放入 `/system/bin`：此后 Termux、adb shell、MT 管理器终端、任意 root shell 里敲 `zcode` 即可打开 TUI，**运行时不需 root**（仅安装步骤需要）。

## 已核实的技术事实

| 事项 | 结论 |
|---|---|
| zcode 运行时要求 | `zcode-app-cli@3.14.3-28`，MIT，`engines.node >= 22.19.0`，bin `zcode`，发行包自包含（unpacked 88MB） |
| dsh | `@deepseek-ai/dsh@0.1.5-rc.3`，MIT，bin `dsh`，`dsh web` 启动 127.0.0.1:3080 浏览器 UI，依赖从 npm registry 解析 |
| 安卓可用的 Node 26 二进制 | Termux 软件仓库 `nodejs_26.4.0-1_aarch64.deb`（标准 Android ELF / bionic，MIT；**仅作二进制来源，与 Termux 应用零依赖**） |
| 终端模拟器 | vendor Termux 的 `terminal-emulator` + `terminal-view` 源码模块（Apache-2.0，纯 Java/Kotlin 无外部依赖），JitPack 亦可作后备 |
| exec 权限约束 | targetSdk ≥ 29 时禁止在应用私有目录 exec；因此 **targetSdk 28**（Termux 同款做法），minSdk 26，compileSdk 34，仅 arm64-v8a |
| 本机构建 | 无安卓 SDK —— 用 GitHub Actions 云端构建（ubuntu + JDK 17 + Gradle），产出 debug 签名 APK 工件 |

## 架构

```
┌─ Phone-Agent.apk（一个 APK，两个桌面图标）────────────────┐
│  assets/runtime.tar.xz   Node26 + npm + .so（fetch 脚本CI预置）│
│  assets/packages/*.tgz    zcode / dsh 离线安装包              │
│                                                            │
│  [Zcode 图标] → TerminalActivity → TerminalView(PTY)        │
│       └ 首次运行: 解压 runtime → npm i -g 本地tgz → exec zcode │
│  [Dsh 图标]  → DshService(前台) 起 dsh web → WebView 全屏     │
│  [Settings]  API Key / npm 镜像 / root 集成开关               │
│                                                            │
│  root 开关 ON → 生成并安装 Magisk 模块:                      │
│    /system/bin/zcode, /system/bin/dsh (包装脚本,755)         │
│    /system/etc/phone_agent/usr/** (node 运行时,世界可读)     │
│    → 任意终端 `zcode` 即开 TUI（无需 root 权限运行）           │
└────────────────────────────────────────────────────────────┘
```

## 文件清单（全部新建于 G:\Phone-Agent\）

```
settings.gradle.kts / build.gradle.kts / gradle.properties   根构建配置(AGP 8.5, Kotlin 2.0, JDK17)
terminal-emulator/, terminal-view/                           vendor 的 Termux 终端库源码 + LICENSE 附注
app/build.gradle.kts                                         minSdk 26, targetSdk 28, compileSdk 34
app/src/main/AndroidManifest.xml                             双 LAUNCHER activity、前台服务、cleartext 仅限 127.0.0.1
app/src/main/res/                                            自适应图标、主题、network_security_config.xml
app/src/main/assets/                                         runtime.tar.xz + packages/*.tgz（CI 脚本产物）
app/src/main/java/com/phoneagent/
  NodeRuntime.kt          解包 runtime 到 filesDir/usr、版本标记、组装环境变量(PATH/LD_LIBRARY_PATH/HOME/npm 镜像)
  Bootstrap.kt            npm i -g 本地 tgz（缺的依赖走 registry），带进度与失败回退
  TermSession.kt          PTY 会话：setup → exec node zcode；失败降级到 busybox 式 sh 并显示错误
  ZcodeTerminalActivity.kt 全屏 TerminalView、软键盘接入、保活前台服务
  DshService.kt           前台服务 spawn `node …/dsh web --no-open`，轮询 3080 就绪
  DshActivity.kt          WebView 全屏加载 http://127.0.0.1:3080，含"服务启动中/失败重试"页
  SettingsActivity.kt     DEEPSEEK_API_KEY、zcode 密钥、npm 镜像(默认 npmmirror)、root 开关、重装按钮
  RootIntegration.kt      libsu：写 Magisk 模块(模块目录含 module.prop/service.sh/系统树)、
                          关幻象进程杀手(device_config max_phantom_processes)、卸载模块；全部显式确认
scripts/versions.env        锁定 NODE=26.4.0-1 / ZCODE=3.14.3-28 / DSH=0.1.5-rc.3
scripts/fetch-runtime.sh    下载 node 及其依赖 deb(校验 sha256) → ar/tar 解包 → 组装 usr 树 → assets/runtime.tar.xz
scripts/fetch-packages.sh   npm pack 两个 CLI → assets/packages/ + integrity 校验文件
.github/workflows/build.yml JDK17 + 跑两个脚本 + gradle assembleDebug + 上传 APK 工件(push/dispatch 触发)
README.md                   中文：架构图、安装、首次启动(需联网拉 dsh 依赖)、任意终端用法、root 说明、排障、致谢
LICENSE / NOTICE.md         Apache-2.0 归属(Termux 库)、Node MIT、zcode/dsh MIT
```

## 实施步骤（顺序执行）

1. **脚手架**：根 Gradle 配置 + `app` 模块 + Manifest（双图标、前台服务、仅 127.0.0.1 明文）+ 资源/图标。
2. **vendor 终端库**：下载 termux-app v0.118 的 `terminal-emulator`、`terminal-view` 源码纳入本仓库为两个库模块（保留 Apache-2.0 头与 NOTICE）。若 GitHub 直连失败，改用 JitPack 依赖 `com.github.termux.termux-app:terminal-emulator/terminal-view`。
3. **运行时与离线包脚本**：`fetch-runtime.sh`（含依赖 deb 自动解析与 sha256 校验）、`fetch-packages.sh`（npm pack + integrity）；本地跑通产出 assets。
4. **核心 Kotlin**：NodeRuntime → Bootstrap → TermSession → ZcodeTerminalActivity（in-app TUI 全链路）。
5. **dsh 链路**：DshService + DshActivity + WebView；设置页（Key/镜像/重装）。
6. **root 深度集成**：RootIntegration（Magisk 模块生成/安装/卸载、幻象进程修复），UI 开关门控 + 二次确认。
7. **CI**：`build.yml` 云端构建 APK 工件；README/LICENSE 收尾。
8. **验证**：CI 构建产出可安装 APK（本机无手机模拟器，真机验证项列入 README 验收清单：in-app TUI、dsh WebView、`adb shell zcode`、Termux 非 root 调用）。

## 风险与对策

- **APK 体积**：Node + ICU + zcode 包 ≈ 70–90MB，可接受（README 注明）。
- **首次启动需联网**：dsh 依赖走 registry；默认 npmmirror 镜像，zcode/dsh 本体已离线打包。
- **软键盘 TUI 体验**：基础软键盘可用，外接键盘最佳；附加键行列为后续增强。
- **WebView 兼容**：dsh 为标准 React UI，System WebView 足够；失败时留浏览器回退入口。
- **targetSdk 28**：仅影响商店分发（本方案侧载安装，无影响）；代码注释说明 W^X 原因。

## 安全（部署规则 1.7）

- API Key 存 `MODE_PRIVATE` SharedPreferences，不落日志、不进仓库；Magisk 模块脚本不含任何密钥。
- 明文流量仅允许 loopback；脚本内所有下载校验 sha256 / npm `dist.integrity`；版本全部锁定在 `versions.env`。
- `/system/bin` 包装脚本不调用 su、不提权，以调用者身份运行——普通终端零 root 即可执行。

## 代码风格（部署规则 1.6）

惯用 Kotlin + kts Gradle；注释只写代码本身表达不了的约束（如 targetSdk 28 的 W^X 原因）；不留注释掉的死代码。
