package com.phoneagent

import android.content.Context
import com.github.luben.zstd.ZstdInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Token usage parsed from dsh session logs. Each session lives under
 * ~/.dsh/sessions/<workspace-slug>/<session>/session*.jsonl[.zstd]; the zstd
 * variant is a concatenation of independent frames (one write per flush), so
 * the stream must be opened in continuous mode to cover every frame.
 *
 * Per-model usage sits on assistant/message events:
 *   data.usage = {inputTokens, outputTokens, totalTokens, cacheReadTokens, reasoningTokens}
 *   data.message.source = {provider, model}, event.time = epoch ms.
 */
object DshUsage {

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun sessionsRoot(ctx: Context): File = File(NodeRuntime.homeDir(ctx), ".dsh/sessions")

    /** Formatted "近 14 天 / 合计 / 按模型" section, mirroring the zcode block. */
    fun query(ctx: Context): String {
        val s = Stats()
        try {
            val root = sessionsRoot(ctx)
            if (!root.isDirectory) return "【DSH】\n（暂无 DSH 会话记录）"
            val files = root.walkTopDown().maxDepth(3)
                .filter { it.isFile && it.name.contains("jsonl") }
                .toList()
            if (files.isEmpty()) return "【DSH】\n（暂无 DSH 会话记录）"
            for (f in files) {
                try {
                    f.inputStream().use { raw ->
                        val input: InputStream =
                            if (f.name.endsWith(".zstd")) {
                                ZstdInputStream(raw).apply { setContinuous(true) }
                            } else {
                                raw
                            }
                        BufferedInputStream(input).use { parse(it, s) }
                    }
                } catch (_: Exception) {
                    // one unreadable session shouldn't sink the whole section
                }
            }
        } catch (e: Exception) {
            return "【DSH】\n读取失败：${e.message}"
        }
        return format(s)
    }

    private class Stats {
        // day -> [input, output, cacheRead, reasoning, requests]
        val daily = HashMap<String, LongArray>()
        // model -> [requests, input, output, reasoning]
        val models = HashMap<String, LongArray>()
    }

    private fun parse(input: InputStream, s: Stats) {
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (!line.contains("\"assistant/message\"")) continue
                val o = try { JSONObject(line) } catch (_: Exception) { continue }
                if (o.optString("type") != "assistant/message") continue
                val data = o.optJSONObject("data") ?: continue
                val usage = data.optJSONObject("usage") ?: continue
                val src = data.optJSONObject("message")?.optJSONObject("source")
                val model = buildString {
                    val p = src?.optString("provider").orEmpty()
                    val m = src?.optString("model").orEmpty()
                    if (p.isNotEmpty()) append(p).append('/')
                    append(m.ifEmpty { "?" })
                }
                // each assistant message with usage = one LLM request
                val day = dayFmt.format(Date(o.optLong("time", 0L)))
                val d = s.daily.getOrPut(day) { LongArray(5) }
                d[0] += usage.optLong("inputTokens", 0)
                d[1] += usage.optLong("outputTokens", 0)
                d[2] += usage.optLong("cacheReadTokens", 0)
                d[3] += usage.optLong("reasoningTokens", 0)
                d[4] += 1
                val m = s.models.getOrPut(model) { LongArray(4) }
                m[0] += 1
                m[1] += usage.optLong("inputTokens", 0)
                m[2] += usage.optLong("outputTokens", 0)
                m[3] += usage.optLong("reasoningTokens", 0)
            }
        }
    }

    private fun format(s: Stats): String {
        val sb = StringBuilder("【DSH】\n近 14 天用量\n──────────────\n")
        if (s.daily.isEmpty()) return sb.append("（暂无记录）").toString()
        val days = s.daily.keys.sortedDescending().take(14)
        var tIn = 0L; var tOut = 0L; var tCache = 0L; var tRe = 0L; var tReq = 0L
        for (day in days) {
            val v = s.daily[day]!!
            tIn += v[0]; tOut += v[1]; tCache += v[2]; tRe += v[3]; tReq += v[4]
            sb.append(day).append("  ↑").append(fmt(v[0]))
                .append(" ↓").append(fmt(v[1]))
                .append("（").append(v[4]).append(" 次请求）\n")
        }
        sb.append("\n合计\n──────────────\n")
            .append("输入 ").append(fmt(tIn)).append(" tokens\n")
            .append("输出 ").append(fmt(tOut)).append(" tokens\n")
            .append("缓存命中 ").append(fmt(tCache)).append(" tokens\n")
            .append("推理 ").append(fmt(tRe)).append(" tokens\n")
            .append("请求 ").append(tReq).append(" 次\n")
        sb.append("\n按模型\n──────────────\n")
        for ((model, v) in s.models.entries.sortedByDescending { it.value[1] }.take(8)) {
            sb.append(model).append("  ")
                .append(v[0]).append(" 次  ↑").append(fmt(v[1]))
                .append(" ↓").append(fmt(v[2]))
                .append(" ◈").append(fmt(v[3])).append("\n")
        }
        return sb.toString()
    }

    private fun fmt(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fk".format(n / 1_000.0)
        else -> n.toString()
    }
}
