package com.phoneagent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

class SettingsActivity : ComponentActivity() {

    private lateinit var pickWorkspace: ActivityResultLauncher<android.content.Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickWorkspace = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) {
                r.data?.data?.let { Workspace.persist(this, it) }
                recreate()
            }
        }
        setContent {
            AppTheme { SettingsScreen() }
        }
    }

    @Composable
    private fun SettingsScreen() {
        Prefs.migrateLegacyKey(this) // move pre-0.7.0 plain-text key to encrypted storage
        var deepseekKey by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(SecretStore.deepseekKey(this))) }
        var registry by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(Prefs.npmRegistry(this))) }
        var proxy by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(Prefs.httpProxy(this))) }
        var workspaceText by remember {
            mutableStateOf(Workspace.workspacePath(this) ?: "未选择（默认使用 APP 私有目录）")
        }
        var updateStatus by remember { mutableStateOf("点击检查后显示版本对比") }
        var updateInfos by remember { mutableStateOf<List<AgentUpdate.Info>?>(null) }
        var rootEnabled by remember { mutableStateOf(false) }
        var rootLog by remember { mutableStateOf("") }

        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            TopAppBar(title = "设置")
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { GroupTitle("凭据与网络") }
                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            FieldLabel("DeepSeek API Key（dsh 使用）")
                            TextField(
                                value = deepseekKey,
                                onValueChange = {
                                    deepseekKey = it
                                    SecretStore.setDeepseekKey(this@SettingsActivity, it.text)
                                },
                                label = "sk-...",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FieldLabel("npm 镜像源")
                            TextField(
                                value = registry,
                                onValueChange = {
                                    registry = it
                                    Prefs.setNpmRegistry(this@SettingsActivity, it.text)
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FieldLabel("HTTP 代理（Node 不读系统 WiFi 代理）")
                            TextField(
                                value = proxy,
                                onValueChange = {
                                    proxy = it
                                    Prefs.setHttpProxy(this@SettingsActivity, it.text)
                                },
                                label = "http://192.168.1.197:7890",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                item { GroupTitle("Agent 组件更新") }
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            val rollbackRows = remember(updateStatus) {
                                listOfNotNull(
                                    AgentSlots.read(this@SettingsActivity, AgentSlots.ZCODE)
                                        ?.takeIf { it.standby != null && it.state == "verified" }
                                        ?.let { "回滚 zcode 到 ${it.standbyVersion}" to "zcode-app-cli" },
                                    AgentSlots.read(this@SettingsActivity, AgentSlots.DSH)
                                        ?.takeIf { it.standby != null && it.state == "verified" }
                                        ?.let { "回滚 dsh 到 ${it.standbyVersion}" to "@deepseek-ai/dsh" },
                                )
                            }
                            M3Text(updateStatus, color = MiuixTheme.colorScheme.onSurface, fontSize = 14.sp)
                            Spacer(Modifier.height(10.dp))
                            UpdateRow("检查更新", enabled = true) {
                                updateStatus = "检查中…"
                                threadRun {
                                    val result = try { AgentUpdate.status(this@SettingsActivity) } catch (e: Exception) { emptyList() }
                                    ui {
                                        updateInfos = result
                                        updateStatus = if (result.isEmpty()) "检查失败：registry 不可达"
                                        else result.joinToString("\n") { i ->
                                            when {
                                                i.latest == "未知" -> "· ${i.name}: registry 不可达"
                                                i.installed == null -> "· ${i.name}: 未安装"
                                                i.updateAvailable -> "· ${i.name}: ${i.installed} → ${i.latest}（可更新）"
                                                else -> "· ${i.name}: ${i.installed}（已是最新）"
                                            }
                                        }
                                    }
                                }
                            }
                            UpdateRow("更新 zcode", enabled = updateInfos?.firstOrNull()?.updateAvailable == true) {
                                updateStatus = "更新 zcode 中…"
                                threadRun {
                                    val log = StringBuilder()
                                    doUpdate("zcode-app-cli", log)
                                    ui { updateStatus = log.toString() }
                                }
                            }
                            UpdateRow("更新 dsh", enabled = updateInfos?.lastOrNull()?.updateAvailable == true) {
                                updateStatus = "更新 dsh 中…"
                                threadRun {
                                    val log = StringBuilder()
                                    doUpdate("@deepseek-ai/dsh", log)
                                    ui { updateStatus = log.toString() }
                                }
                            }
                            M3Text(
                                "更新装入备用槽，下次启动切换；启动失败自动回滚。",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            rollbackRows.forEach { row ->
                                UpdateRow(row.first, enabled = true) {
                                    threadRun {
                                        val log = StringBuilder()
                                        AgentUpdate.rollback(this@SettingsActivity, row.second) { line -> log.append(line).append('\n') }
                                        ui { updateStatus = log.toString().trim() }
                                    }
                                }
                            }
                        }
                    }
                }

                item { GroupTitle("工作区") }
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            M3Text(workspaceText, color = MiuixTheme.colorScheme.onSurface, fontSize = 14.sp)
                            Spacer(Modifier.height(10.dp))
                            var allFiles by remember {
                                mutableStateOf(StorageAccess.isAllFilesGranted(this@SettingsActivity))
                            }
                            M3Text(
                                if (allFiles) "已授予所有文件访问：agent 的文件对话框可直接浏览本机全部目录"
                                else "授予「所有文件访问」后，agent 的文件对话框可直接选择本机任意目录（推荐）",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 13.sp,
                            )
                            Spacer(Modifier.height(10.dp))
                            if (StorageAccess.needsSpecialScreen(this@SettingsActivity)) {
                                UpdateRow("授予所有文件访问", enabled = true) {
                                    startActivity(StorageAccess.buildAllFilesIntent())
                                }
                            } else {
                                M3Text(
                                    "本机存储可直接访问（Android 10 及以下自动生效）",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 13.sp,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            UpdateRow("精确选择单个目录（SAF，可选）", enabled = true) {
                                pickWorkspace.launch(
                                    android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
                                        .putExtra("android.provider.extra.INITIAL_URI", "content://com.android.externalstorage.documents/document/primary%3A")
                                )
                            }
                            UpdateRow("清除 SAF 目录", enabled = Workspace.workspacePath(this@SettingsActivity) != null) {
                                Workspace.clear(this@SettingsActivity)
                                workspaceText = StorageAccess.defaultWorkspace(this@SettingsActivity)
                            }
                        }
                    }
                }

                item { GroupTitle("Shizuku（非 root 的 adb 权限）") }
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            var shizukuState by remember {
                                mutableStateOf(if (ShizukuHelper.granted()) "已授权"
                                    else if (ShizukuHelper.available()) "已运行，未授权"
                                    else "未运行（安装 Shizuku 后启动一次）")
                            }
                            var shizukuLog by remember { mutableStateOf("") }
                            M3Text(
                                "opencode webui 在非 root 设备上通过 Shizuku（adb 权限）启动：" +
                                    "安装 Shizuku 应用 → 开启一次（无线调试或 adb）→ 点下方请求授权。" +
                                    "设备重启后需要重新启动 Shizuku。",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            M3Text("状态：$shizukuState", color = MiuixTheme.colorScheme.onSurface, fontSize = 14.sp)
                            Spacer(Modifier.height(8.dp))
                            UpdateRow("请求 Shizuku 权限", enabled = ShizukuHelper.available()) {
                                ShizukuHelper.request(this@SettingsActivity)
                                threadRun {
                                    Thread.sleep(2500)
                                    ui {
                                        shizukuState = if (ShizukuHelper.granted()) "已授权"
                                        else if (ShizukuHelper.available()) "已运行，未授权"
                                        else "未运行"
                                    }
                                }
                            }
                            UpdateRow("修复幻象进程限制（Shizuku）", enabled = ShizukuHelper.granted()) {
                                threadRun {
                                    val lines = ShizukuHelper.fixPhantomProcesses()
                                    ui { shizukuLog = lines.joinToString("\n") }
                                }
                            }
                            UpdateRow("授权「所有文件访问」（Shizuku）", enabled = ShizukuHelper.granted()) {
                                threadRun {
                                    val lines = ShizukuHelper.grantAllFilesAccess()
                                    ui { shizukuLog = lines.joinToString("\n") }
                                }
                            }
                            UpdateRow("查看监听端口（Shizuku）", enabled = ShizukuHelper.granted()) {
                                threadRun {
                                    val lines = ShizukuHelper.portReport()
                                    ui { shizukuLog = lines }
                                }
                            }
                            UpdateRow("加入电池优化白名单（Shizuku）", enabled = ShizukuHelper.granted()) {
                                threadRun {
                                    val lines = ShizukuHelper.whitelistFromBatteryOptimization()
                                    ui { shizukuLog = lines.joinToString("\n") }
                                }
                            }
                            UpdateRow("授予通知权限（Shizuku）", enabled = ShizukuHelper.granted()) {
                                threadRun {
                                    val lines = ShizukuHelper.grantNotifications()
                                    ui { shizukuLog = lines.joinToString("\n") }
                                }
                            }
                            if (shizukuLog.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                M3Text(shizukuLog, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
                            }
                        }
                    }
                }

                item { GroupTitle("root 深度集成（可选）") }
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            top.yukonga.miuix.kmp.extra.SuperSwitch(
                                title = "启用 root 集成",
                                summary = "安装 Magisk 模块暴露系统级 zcode/dsh 命令",
                                checked = rootEnabled,
                                onCheckedChange = { rootEnabled = it },
                            )
                            if (rootEnabled) {
                                Spacer(Modifier.height(8.dp))
                                UpdateRow("安装系统级命令", enabled = true) {
                                    threadRun {
                                        val lines = RootIntegration.installModule(this@SettingsActivity)
                                        ui { rootLog = lines.joinToString("\n") }
                                    }
                                }
                                UpdateRow("卸载系统级命令", enabled = true) {
                                    threadRun {
                                        val lines = RootIntegration.uninstallModule()
                                        ui { rootLog = lines.joinToString("\n") }
                                    }
                                }
                                UpdateRow("修复幻象进程限制", enabled = true) {
                                    threadRun {
                                        val lines = RootIntegration.fixPhantomProcesses()
                                        ui { rootLog = lines.joinToString("\n") }
                                    }
                                }
                            }
                            if (rootLog.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                M3Text(rootLog, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
                            }
                        }
                    }
                }

                item { GroupTitle("维护") }
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            UpdateRow("重建桌面快捷方式", enabled = true) {
                                threadRun {
                                    val lines = Shortcuts.recreateAll(this@SettingsActivity)
                                    ui { rootLog = lines.joinToString("\n") }
                                }
                            }
                            UpdateRow("用量统计", enabled = true) {
                                startActivity(Intent(this@SettingsActivity, UsageActivity::class.java))
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(12.dp))
                    UpdateRow("重新初始化运行时", enabled = true) {
                        NodeRuntime.usrDir(this@SettingsActivity).deleteRecursively()
                        NodeRuntime.pkgDir(this@SettingsActivity).deleteRecursively()
                        NodeRuntime.homeDir(this@SettingsActivity).deleteRecursively()
                        filesDir.listFiles()?.filter { it.name.startsWith(".runtime-") || it.name.startsWith(".zcode-web-") }?.forEach { it.delete() }
                        android.widget.Toast.makeText(this@SettingsActivity, "已重置", android.widget.Toast.LENGTH_LONG).show()
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    private fun doUpdate(pkg: String, log: StringBuilder) {
        val ver = AgentUpdate.status(this).firstOrNull { it.name == pkg }?.takeIf { it.updateAvailable }?.latest
        if (ver == null) { log.appendLine("[update] $pkg 无可用更新"); return }
        val tgz = AgentUpdate.fetchTarball(this, pkg, ver) { line -> log.appendLine(line) } ?: return
        AgentUpdate.applyUpdate(this, pkg, tgz) { line -> log.appendLine(line) }
    }
    private fun threadRun(block: () -> Unit) {
        kotlin.concurrent.thread { block() }
    }

    private fun ui(block: () -> Unit) {
        runOnUiThread(block)
    }
}
