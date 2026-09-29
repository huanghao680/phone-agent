package com.phoneagent

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/** Usage stats from the zcode CLI's SQLite model_usage ledger. */
class UsageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme { UsageScreen() }
        }
    }

    @Composable
    private fun UsageScreen() {
        var text by remember { mutableStateOf("读取中…") }
        LaunchedEffect(Unit) {
            text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { query() }
        }
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            TopAppBar(title = "用量统计")
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
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
        }
    }

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

    private fun fmt(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fk".format(n / 1_000.0)
        else -> n.toString()
    }
}
