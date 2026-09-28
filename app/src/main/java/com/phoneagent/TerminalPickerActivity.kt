package com.phoneagent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Terminal entry point: pick which agent TUI to run in the shared terminal.
 * Zcode and dsh run on the embedded Node; opencode ships glibc-compiled Bun
 * binaries with no bionic build, so it is listed with an honest unsupported
 * note. Also hosts the settings and usage-stat entries.
 */
class TerminalPickerActivity : ComponentActivity() {

    private data class Choice(val id: String, val title: String, val desc: String, val available: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                PickerScreen()
            }
        }
    }

    @Composable
    private fun PickerScreen() {
        var selected by remember { mutableStateOf<String?>(null) }
        // availability depends on staged binaries; recomputed after extraction
        var binReadyTick by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            // stage the codex/claude binaries on first open (idempotent), then
            // refresh card availability
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (NodeRuntime.isRuntimeExtracted(this@TerminalPickerActivity)) {
                    NodeRuntime.copyPackages(this@TerminalPickerActivity)
                }
            }
            binReadyTick++
        }
        val codexAvailable = remember(binReadyTick) { BinaryAgents.isReady(this, BinaryAgents.CODEX) }
        val claudeAvailable = remember(binReadyTick) { BinaryAgents.isReady(this, BinaryAgents.CLAUDE) }
        val choices = listOf(
            Choice("zcode", "Zcode TUI", "Z.ai 的编码 Agent（内嵌 Node，自动安装）", true),
            Choice("dsh", "DeepSeek Harness TUI", "DeepSeek 官方 Harness（内嵌 Node，自动安装）", true),
            Choice(
                "codex",
                "Codex TUI",
                if (codexAvailable) "OpenAI 的编码 Agent（静态 musl 二进制，安卓原生运行）"
                else "二进制未就绪：重新打开 APP 解压运行时后再试",
                codexAvailable,
            ),
            Choice(
                "claude",
                "Claude Code TUI",
                if (claudeAvailable) "Anthropic 的编码 Agent（musl 二进制 + 自带 loader）"
                else "二进制未就绪：重新打开 APP 解压运行时后再试",
                claudeAvailable,
            ),
        )
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            TopAppBar(title = "终端")
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SectionTitle("选择要运行的 TUI")
                }
                items(choices, key = { it.id }) { c ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = c.available) { selected = c.id },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                M3Text(
                                    c.title,
                                    color = if (c.available) MiuixTheme.colorScheme.onSurface
                                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (!c.available) {
                                    M3Text("不可用", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            M3Text(
                                c.desc,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                item {
                    SectionTitle("工具")
                }
                item {
                    PickerCard("用量统计", "zcode 会话的 token 用量（本机数据库）") {
                        startActivity(Intent(this@TerminalPickerActivity, UsageActivity::class.java))
                    }
                }
                item {
                    PickerCard("设置", "API Key / 镜像 / 代理 / 工作区 / 更新 / root") {
                        startActivity(Intent(this@TerminalPickerActivity, SettingsActivity::class.java))
                    }
                }
                item {
                    Spacer(Modifier.height(24.dp))
                    M3Text(
                        "Phone Agent v${BuildConfig.VERSION_NAME}",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        selected?.let { id ->
            // handled outside composition to avoid double launch on recomposition
            selected = null
            when (id) {
                "zcode", "dsh" -> startActivity(
                    Intent(this, ZcodeTerminalActivity::class.java).putExtra("agent", id)
                )
                "codex" -> startActivity(Intent(this, CodexTerminalActivity::class.java))
                "claude" -> startActivity(Intent(this, ClaudeTerminalActivity::class.java))
            }
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        M3Text(
            text,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
    }

    @Composable
    private fun PickerCard(title: String, desc: String, onClick: () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
            Column(Modifier.padding(16.dp)) {
                M3Text(title, color = MiuixTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                M3Text(desc, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
            }
        }
    }
}

/** Shared app theme: Miuix with system dark/light detection. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (dark) top.yukonga.miuix.kmp.theme.darkColorScheme() else top.yukonga.miuix.kmp.theme.lightColorScheme()
    MiuixTheme(colors = colors, content = content)
}
