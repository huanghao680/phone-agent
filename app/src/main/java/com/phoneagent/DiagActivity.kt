package com.phoneagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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

/**
 * Diagnostics sheet: one place to see what each engine is doing. Aggregates the
 * three agent log tails, live port state, crash signatures and watchdog status —
 * everything that used to require an adb shell and a memory of cache paths.
 */
class DiagActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme { Screen() }
        }
    }

    @Composable
    private fun Screen() {
        var text by remember { mutableStateOf("读取中…") }
        LaunchedEffect(Unit) {
            text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { collect() }
        }
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            TopAppBar(title = "诊断")
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Card(Modifier.fillMaxSize()) {
                        M3Text(
                            text,
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    private fun collect(): String {
        val sb = StringBuilder()
        sb.append("看门狗\n──────────────\n")
            .append("运行中：").append(if (Watchdog.running) "是" else "否").append('\n')
            .append("熔断：").append(if (Watchdog.enabled) "正常" else "已暂停").append('\n')
        for (a in listOf("zcode", "dsh", "opencode")) {
            sb.append("  ").append(a).append("：").append(Watchdog.stateOf(a)).append('\n')
        }

        sb.append("\n端口（经 Shizuku 读取全机监听）\n──────────────\n")
        val ports = if (ShizukuHelper.granted()) {
            val all = ShizukuHelper.listeningPorts()
            val ours = mapOf(3030 to "zcode web", 3080 to "dsh web", 4096 to "opencode webui", 8899 to "工具通道")
            if (all.isEmpty()) "（读不到）" else all.joinToString("\n") { p ->
                "  $p${ours[p]?.let { "  $it" } ?: ""}"
            }
        } else {
            "（Shizuku 未授权，只能探测自己的端口）\n" +
                listOf(3030, 3080, 4096, 8899).joinToString("\n") { p ->
                    "  $p：${if (portOpen(p)) "在监听" else "未监听"}"
                }
        }
        sb.append(ports).append('\n')

        for (a in listOf("zcode", "dsh", "opencode")) {
            val f = File(cacheDir, when (a) {
                "zcode" -> "zcode-web.log"
                "dsh" -> "dsh.log"
                else -> "opencode.log"
            })
            sb.append("\n").append(a).append(" 日志（")
                .append(if (f.isFile) "%.1fKB".format(f.length() / 1024.0) else "无").append("）\n──────────────\n")
            if (f.isFile) {
                val lines = try {
                    f.readLines().takeLast(12)
                } catch (_: Exception) {
                    listOf("（读取失败）")
                }
                for (l in lines) sb.append("  ").append(l.take(160)).append('\n')
            }
        }
        return sb.toString()
    }

    private fun portOpen(port: Int): Boolean = try {
        java.net.Socket().use {
            it.connect(java.net.InetSocketAddress("127.0.0.1", port), 600)
            true
        }
    } catch (_: Exception) {
        false
    }
}
