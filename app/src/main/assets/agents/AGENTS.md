# phone-agent 运行环境说明（必读）

本机是 phone-agent APK 的内置运行时：Android (bionic) 上独立运行的 agent 环境，**不依赖、也没有 Termux 应用**。

## 环境事实

- 工作区（当前目录）通常是 `/data/user/0/com.phoneagent/files/home`——应用私有目录，可读写、**可执行**。
- 共享存储（`/sdcard`、`/storage/emulated/0`、`/storage/emulated/0/Android/data/com.phoneagent`）**可读写但不可执行**（FUSE noexec）：任何二进制/脚本要执行，必须放在应用私有目录。
- **没有 `/tmp`**（根分区只读）。临时文件一律用 `$TMPDIR`。
- 工具链齐全：bash、coreutils、rg、curl、jq、git、python3。git 的 exec-path/CA 已由环境变量修复。
- 网络走本 App 注入的 CA/代理环境变量。**zcode 的 WebFetch 工具在本环境不可用**（会卡满超时），抓网页请用 bash + node（fetch/https）。
- dsh 会话可能运行在 workspace-write 沙盒下：工作区与 `$TMPDIR` 可写，其余路径写入被拒、`/sdcard` 不可见——这是预期行为，不是故障。

## 本机设备工具（phone-agent 提供）

本机回环地址 127.0.0.1:8899 有一个设备工具服务，`usr/bin` 里有现成封装，**可直接调用**：

- `pa-screenshot > $TMPDIR/shot.png` —— 截取当前屏幕（PNG 到 stdout）
- `pa-ui-dump` —— 当前界面 UI 层级树（JSON，`xml` 字段是 uiautomator dump）
- `pa-tap <x> <y>` —— 点击绝对像素坐标（越界坐标会被拒绝）
- `pa-input <文本>` —— 输入文本；ASCII 直接键入，**中文等非 ASCII 走剪贴板粘贴**（需先用 `pa-tap` 聚焦目标输入框）
- `pa-key <KEYCODE>` —— 按键，如 `pa-key KEYCODE_BACK`
- `pa-shell <命令>` —— 以 shell(uid 2000) 权限执行命令（需 Shizuku 授权；可读全机端口、appops、dumpsys）
- `pa-clip` 读剪贴板 / `pa-clip <文本>` 写剪贴板
- `pa-ports` / `pa-device` —— 本机监听端口表 / 设备信息

坐标一律绝对像素（屏幕原点左上）。操作前先 `pa-ui-dump` 看清界面再动手。

## 更新政策（必须遵守）

- **禁止自行更新 agent 本体或任何全局包。** 不要运行 `npm install -g`、`npm i -g`、`corepack` 等全局安装/更新命令：本环境的全局前缀不可写，这些命令必然失败，并可能破坏槽位布局。
- agent（zcode / dsh）的版本由 **App 的 A/B 槽位系统**管理：用户在 **App 设置 → Agent 更新** 里更新——新版本装入备用槽，下次启动切换，启动失败自动回滚。
- 如果用户要求"更新 dsh / 更新 zcode / 升级版本"：告知请到 **App 的设置页 → Agent 更新** 操作，不要在会话里执行任何更新命令。
- 例外——dsh 插件允许安装：用 `dsh plugin --profile <name> add <包名>` 或 Web 界面的插件页；插件装在 profile 目录，不影响 agent 本体。
