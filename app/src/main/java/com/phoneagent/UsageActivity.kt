package com.phoneagent

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Usage stats + per-session management across zcode / dsh / opencode. */
class UsageActivity : ComponentActivity() {

    private sealed interface Screen {
        data object Overview : Screen
        data class AgentList(val agent: String) : Screen
        data class Detail(val agent: String, val id: String) : Screen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme { Root() }
        }
    }

    @Composable
    private fun Root() {
        var screen by remember { mutableStateOf<Screen>(Screen.Overview) }
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            TopAppBar(
                title = when (val s = screen) {
                    Screen.Overview -> "用量统计"
                    is Screen.AgentList -> agentLabel(s.agent) + " 会话"
                    is Screen.Detail -> "会话详情"
                },
            )
            when (val s = screen) {
                Screen.Overview -> OverviewTab(onOpenAgent = { screen = Screen.AgentList(it) })
                is Screen.AgentList -> AgentListTab(
                    agent = s.agent,
                    onOpen = { screen = Screen.Detail(s.agent, it) },
                )
                is Screen.Detail -> DetailTab(
                    agent = s.agent,
                    id = s.id,
                    onDeleted = { screen = Screen.AgentList(s.agent) },
                )
            }
        }
    }

    // ───────────────────────── overview ─────────────────────────

    @Composable
    private fun OverviewTab(onOpenAgent: (String) -> Unit) {
        var text by remember { mutableStateOf("读取中…") }
        LaunchedEffect(Unit) {
            text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { query() }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxSize()) {
                    M3Text(
                        text,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            item {
                M3Text("会话管理", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
            }
            for (agent in listOf("zcode", "dsh", "opencode")) {
                item {
                    Card(Modifier.fillMaxSize().clickable { onOpenAgent(agent) }) {
                        Column(Modifier.padding(16.dp)) {
                            M3Text(
                                agentLabel(agent) + " 会话",
                                color = MiuixTheme.colorScheme.onSurface,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            M3Text(
                                when (agent) {
                                    "zcode" -> "标题 / 模型 / 工具调用 / token / 时长 / 删除"
                                    "dsh" -> "标题 / 工具调用与错误 / token / 轮次 / 重试 / 删除"
                                    else -> "标题 / token 聚合 / 成本 / 消息数"
                                },
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }

    // ───────────────────────── agent list ─────────────────────────

    @Composable
    private fun AgentListTab(agent: String, onOpen: (String) -> Unit) {
        var sessions by remember { mutableStateOf<List<SessionData.AgentSession>?>(null) }
        LaunchedEffect(Unit) {
            sessions = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                when (agent) {
                    "zcode" -> SessionData.zcodeList(this@UsageActivity)
                    "dsh" -> SessionData.dshList(this@UsageActivity)
                    else -> SessionData.opencodeList(this@UsageActivity)
                }
            }
        }
        val list = sessions
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                list == null -> item { M3Text("读取中…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp) }
                list.isEmpty() -> item {
                    M3Text(
                        "暂无会话（对应的 agent 还没有跑过对话）",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                    )
                }
                else -> for (s in list) {
                    item {
                        Card(Modifier.fillMaxSize().clickable { onOpen(s.id) }) {
                            Column(Modifier.padding(16.dp)) {
                                Row {
                                    M3Text(
                                        s.title.ifEmpty { s.id.take(18) },
                                        color = MiuixTheme.colorScheme.onSurface,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (s.subagent) {
                                        Spacer(Modifier.width(6.dp))
                                        M3Text("子代理", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                M3Text(
                                    fmtDate(s.createdAt) + "  ·  " + fmtDur(s.durationMs),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 12.sp,
                                )
                                Spacer(Modifier.height(6.dp))
                                M3Text(
                                    "🔧 ${s.toolCalls}  ·  ✉ ${s.requests}  ·  ↑${fmt(s.inputTokens)} ↓${fmt(s.outputTokens)}" +
                                        (if (s.cacheTokens > 0) "  ⚡${fmt(s.cacheTokens)}" else "") +
                                        (if (s.reasoningTokens > 0) "  ◈${fmt(s.reasoningTokens)}" else ""),
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                                if (s.model.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    M3Text(s.model, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ───────────────────────── detail ─────────────────────────

    @Composable
    private fun DetailTab(agent: String, id: String, onDeleted: () -> Unit) {
        var detail by remember { mutableStateOf<SessionData.SessionDetail?>(null) }
        var confirmDelete by remember { mutableStateOf(false) }
        LaunchedEffect(id) {
            detail = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                when (agent) {
                    "zcode" -> SessionData.zcodeDetail(this@UsageActivity, id)
                    "dsh" -> SessionData.dshDetail(this@UsageActivity, id)
                    else -> SessionData.opencodeDetail(this@UsageActivity, id)
                }
            }
        }
        val d = detail
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (d == null) {
                item { M3Text("读取中…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp) }
            } else {
                item {
                    Card(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(16.dp)) {
                            M3Text(
                                d.session.title.ifEmpty { d.session.id.take(18) },
                                color = MiuixTheme.colorScheme.onSurface,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            M3Text(
                                fmtDate(d.session.createdAt) + " → " + fmtDate(d.session.updatedAt) +
                                    "  ·  " + fmtDur(d.session.durationMs),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 12.sp,
                            )
                            if (d.session.directory.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                M3Text(
                                    d.session.directory,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            statRow("模型请求", "${d.session.requests} 次")
                            statRow("用户消息", "${d.session.userMessages}")
                            statRow("助手回复", "${d.session.assistantMessages}")
                            statRow("工具调用", "${d.session.toolCalls}")
                            statRow("输入 tokens", fmt(d.session.inputTokens))
                            statRow("输出 tokens", fmt(d.session.outputTokens))
                            if (d.session.cacheTokens > 0) statRow("缓存命中", fmt(d.session.cacheTokens))
                            if (d.session.reasoningTokens > 0) statRow("推理 tokens", fmt(d.session.reasoningTokens))
                            if (d.session.retries > 0) statRow("LLM 重试", "${d.session.retries} 次")
                            if (d.session.model.isNotEmpty()) statRow("模型", d.session.model)
                        }
                    }
                }
                if (d.models.isNotEmpty()) {
                    item { M3Text("按模型", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp) }
                    item {
                        Card(Modifier.fillMaxSize()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (m in d.models) {
                                    M3Text(
                                        "${m.model}  ·  ${m.requests} 次  ↑${fmt(m.input)} ↓${fmt(m.output)}" +
                                            (if (m.reasoning > 0) " ◈${fmt(m.reasoning)}" else ""),
                                        color = MiuixTheme.colorScheme.onSurface,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
                if (d.tools.isNotEmpty()) {
                    item { M3Text("按工具", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp) }
                    item {
                        Card(Modifier.fillMaxSize()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (t in d.tools) {
                                    M3Text(
                                        t.name + "  ·  ${t.count} 次" +
                                            (if (t.durationMs > 0) "  ·  " + fmtDur(t.durationMs) else "") +
                                            (if (t.errors > 0) "  ·  ⚠${t.errors}" else ""),
                                        color = if (t.errors > 0) Color(0xFFF87171) else MiuixTheme.colorScheme.onSurface,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
                if (d.inputs.isNotEmpty()) {
                    item { M3Text("近期输入", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp) }
                    item {
                        Card(Modifier.fillMaxSize()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (inp in d.inputs) {
                                    M3Text(
                                        "· " + inp.replace("\n", " "),
                                        color = MiuixTheme.colorScheme.onSurface,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }
                if (agent != "opencode") {
                    item {
                        Card(Modifier.fillMaxSize().clickable { confirmDelete = true }) {
                            M3Text(
                                "删除该会话",
                                color = Color(0xFFF87171),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                } else {
                    item {
                        M3Text(
                            "opencode 会话为事件溯源存储，暂不支持在 App 内删除。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { M3Text("删除会话？") },
                text = {
                    M3Text(
                        "将删除该会话的全部消息、用量与工具记录，且不可恢复。" +
                            (if (agent == "zcode") "建议先关闭正在运行的 zcode 会话。" else ""),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        Thread {
                            when (agent) {
                                "zcode" -> SessionData.zcodeDelete(this@UsageActivity, id)
                                "dsh" -> SessionData.dshDelete(this@UsageActivity, id)
                            }
                            runOnUiThread { onDeleted() }
                        }.start()
                    }) { M3Text("删除", color = Color(0xFFF87171)) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { M3Text("取消") } },
            )
        }
    }

    @Composable
    private fun statRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            M3Text(label, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
            M3Text(value, color = MiuixTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }

    // ───────────────────────── overview query (kept) ─────────────────────────

    private fun db(): File = File(NodeRuntime.homeDir(this), ".zcode/cli/db/db.sqlite")

    private fun query(): String {
        val f = db()
        if (!f.exists()) return getString(R.string.usage_empty)
        return try {
            // SQLite WAL: copy db + wal into cache so the live CLI file is untouched
            val tmp = File(cacheDir, "usage-copy.sqlite")
            val wal = File(f.parentFile, "db.sqlite-wal")
            f.inputStream().use { it.copyTo(tmp.outputStream()) }
            if (wal.exists()) wal.inputStream().use { it.copyTo(File(cacheDir, "usage-copy.sqlite-wal").outputStream()) }
            val dbc = SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val sb = StringBuilder()

            val daily = linkedMapOf<String, LongArray>() // [in, out, reasoning, requests]
            dbc.rawQuery(
                "SELECT date(started_at/1000,'unixepoch','localtime') AS d," +
                    " SUM(input_tokens), SUM(output_tokens), SUM(reasoning_tokens), COUNT(*)" +
                    " FROM model_usage GROUP BY d ORDER BY d DESC LIMIT 14",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    daily[c.getString(0) ?: "?"] =
                        longArrayOf(c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4))
                }
            }
            var totalIn = 0L; var totalOut = 0L; var totalRe = 0L; var totalReq = 0L
            sb.append("【Zcode】\n近 14 天用量\n──────────────\n")
            for ((day, v) in daily) {
                totalIn += v[0]; totalOut += v[1]; totalRe += v[2]; totalReq += v[3]
                sb.append(day).append("  ↑").append(fmt(v[0]))
                    .append(" ↓").append(fmt(v[1]))
                    .append("（").append(v[3]).append(" 次请求）\n")
            }
            if (daily.isEmpty()) sb.append("（暂无记录）\n")
            sb.append("\n合计\n──────────────\n")
                .append("输入 ").append(fmt(totalIn)).append(" tokens\n")
                .append("输出 ").append(fmt(totalOut)).append(" tokens\n")
                .append("推理 ").append(fmt(totalRe)).append(" tokens\n")
                .append("请求 ").append(totalReq).append(" 次\n")

            dbc.rawQuery(
                "SELECT provider_id || '/' || model_id, COUNT(*)," +
                    " SUM(input_tokens), SUM(output_tokens), SUM(reasoning_tokens)" +
                    " FROM model_usage GROUP BY provider_id, model_id ORDER BY 3 DESC LIMIT 8",
                null,
            ).use { c ->
                if (c.count > 0) {
                    sb.append("\n按模型\n──────────────\n")
                    while (c.moveToNext()) {
                        sb.append(c.getString(0)).append("  ")
                            .append(c.getLong(1)).append(" 次  ↑")
                            .append(fmt(c.getLong(2))).append(" ↓")
                            .append(fmt(c.getLong(3)))
                            .append(" ◈").append(fmt(c.getLong(4))).append("\n")
                    }
                }
            }
            dbc.close()
            tmp.delete()
            sb.append('\n').append(DshUsage.query(this))
            sb.toString()
        } catch (e: Exception) {
            "读取用量失败：${e.message}"
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun agentLabel(agent: String): String = when (agent) {
        "zcode" -> "Zcode"
        "dsh" -> "DeepSeek Harness"
        else -> "opencode"
    }

    private fun fmtDate(ms: Long): String =
        if (ms <= 0) "?" else SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ms))

    private fun fmtDur(ms: Long): String = when {
        ms <= 0 -> ""
        ms < 60_000 -> "${ms / 1000}s"
        ms < 3_600_000 -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
        else -> "${ms / 3_600_000}h${(ms % 3_600_000) / 60_000}m"
    }

    private fun fmt(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fk".format(n / 1_000.0)
        else -> n.toString()
    }
}
