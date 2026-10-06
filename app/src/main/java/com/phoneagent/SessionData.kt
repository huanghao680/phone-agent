package com.phoneagent

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.github.luben.zstd.ZstdInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-session view over the three agents' local stores.
 *
 *  zcode    ~/.zcode/cli/db/db.sqlite         session + model_usage + tool_usage (WAL SQLite)
 *  dsh      ~/.dsh/sessions/<slug>/<dir>/     session.v3.jsonl[.zstd] event log (multi-frame zstd)
 *  opencode ~/.local/share/opencode/opencode.db  session row already carries token aggregates
 */
object SessionData {

    data class ModelRow(val model: String, val requests: Int, val input: Long, val output: Long, val reasoning: Long)
    data class ToolRow(val name: String, val count: Int, val durationMs: Long, val errors: Int)

    data class AgentSession(
        val agent: String, // zcode | dsh | opencode
        val id: String,
        val title: String,
        val directory: String,
        val createdAt: Long,
        val updatedAt: Long,
        val requests: Int,
        val userMessages: Int,
        val assistantMessages: Int,
        val toolCalls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val reasoningTokens: Long,
        val cacheTokens: Long,
        val durationMs: Long,
        val retries: Int,
        val subagent: Boolean,
        val model: String,
    )

    data class SessionDetail(
        val session: AgentSession,
        val models: List<ModelRow>,
        val tools: List<ToolRow>,
        val inputs: List<String>,
    )

    // ───────────────────────── zcode ─────────────────────────

    private fun zcodeDb(ctx: Context): File = File(NodeRuntime.homeDir(ctx), ".zcode/cli/db/db.sqlite")

    fun zcodeAvailable(ctx: Context): Boolean = zcodeDb(ctx).exists()

    fun zcodeList(ctx: Context): List<AgentSession> {
        val f = zcodeDb(ctx)
        if (!f.exists()) return emptyList()
        val out = mutableListOf<AgentSession>()
        return try {
            openReadOnly(ctx, f) { dbc ->
                // message roles come from a JSON blob; Android's bundled SQLite may
                // lack json_extract, so count roles in Kotlin
                val roles = HashMap<String, Pair<Int, Int>>() // session -> (user, assistant)
                dbc.rawQuery("SELECT session_id, data FROM message", null).use { c ->
                    while (c.moveToNext()) {
                        try {
                            val d = JSONObject(c.getString(1))
                            val role = d.optString("role")
                            val pair = roles.getOrPut(c.getString(0)) { Pair(0, 0) }
                            if (role == "user") roles[c.getString(0)] = Pair(pair.first + 1, pair.second)
                            else if (role == "assistant") roles[c.getString(0)] = Pair(pair.first, pair.second + 1)
                        } catch (_: Exception) {
                        }
                    }
                }
                dbc.rawQuery(
                    "SELECT s.id, COALESCE(s.title, s.id), COALESCE(s.directory, ''), s.time_created, s.time_updated," +
                        " COUNT(m.id), COALESCE(SUM(m.tool_call_count),0), COALESCE(SUM(m.input_tokens),0)," +
                        " COALESCE(SUM(m.output_tokens),0), COALESCE(SUM(m.reasoning_tokens),0)," +
                        " COALESCE(SUM(m.cache_read_input_tokens + m.cache_creation_input_tokens),0)," +
                        " COALESCE(SUM(m.duration_ms),0), COALESCE(SUM(m.retry_count),0)," +
                        " COALESCE(MAX(m.provider_id || '/' || m.model_id), '')" +
                        " FROM session s LEFT JOIN model_usage m ON m.session_id = s.id" +
                        " GROUP BY s.id ORDER BY s.time_updated DESC",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val (u, a) = roles[id] ?: Pair(0, 0)
                        out += AgentSession(
                            agent = "zcode", id = id, title = c.getString(1), directory = c.getString(2),
                            createdAt = c.getLong(3), updatedAt = c.getLong(4),
                            requests = c.getInt(5), userMessages = u, assistantMessages = a,
                            toolCalls = c.getInt(6), inputTokens = c.getLong(7), outputTokens = c.getLong(8),
                            reasoningTokens = c.getLong(9), cacheTokens = c.getLong(10),
                            durationMs = c.getLong(11), retries = c.getInt(12),
                            subagent = false, model = c.getString(13),
                        )
                    }
                }
            }
            out
        } catch (_: Exception) {
            out
        }
    }

    fun zcodeDetail(ctx: Context, id: String): SessionDetail {
        val sess = zcodeList(ctx).firstOrNull { it.id == id }
            ?: return SessionDetail(AgentSession("zcode", id, id, "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, ""), emptyList(), emptyList(), emptyList())
        val models = mutableListOf<ModelRow>()
        val tools = mutableListOf<ToolRow>()
        val inputs = mutableListOf<String>()
        try {
            openReadOnly(ctx, zcodeDb(ctx)) { dbc ->
                dbc.rawQuery(
                    "SELECT provider_id || '/' || model_id, COUNT(*), SUM(input_tokens), SUM(output_tokens), SUM(reasoning_tokens)" +
                        " FROM model_usage WHERE session_id = ? GROUP BY provider_id, model_id ORDER BY 3 DESC",
                    arrayOf(id),
                ).use { c ->
                    while (c.moveToNext()) models += ModelRow(c.getString(0), c.getInt(1), c.getLong(2), c.getLong(3), c.getLong(4))
                }
                dbc.rawQuery(
                    "SELECT tool_name, COUNT(*), COALESCE(SUM(duration_ms),0)," +
                        " SUM(CASE WHEN error_type IS NOT NULL THEN 1 ELSE 0 END)" +
                        " FROM tool_usage WHERE session_id = ? GROUP BY tool_name ORDER BY 2 DESC",
                    arrayOf(id),
                ).use { c ->
                    while (c.moveToNext()) tools += ToolRow(c.getString(0), c.getInt(1), c.getLong(2), c.getInt(3))
                }
                dbc.rawQuery(
                    "SELECT text FROM input_history WHERE session_id = ? ORDER BY time_created DESC LIMIT 8",
                    arrayOf(id),
                ).use { c ->
                    while (c.moveToNext()) inputs += c.getString(0).take(120)
                }
            }
        } catch (_: Exception) {
        }
        return SessionDetail(sess, models, tools, inputs)
    }

    /** Deletes every row belonging to the session. Returns rows removed. */
    fun zcodeDelete(ctx: Context, id: String): Int {
        val f = zcodeDb(ctx)
        if (!f.exists()) return 0
        var rows = 0
        val dbc = SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        dbc.beginTransaction()
        try {
            for (t in arrayOf("message", "part", "model_usage", "tool_usage", "turn_usage", "input_history", "session_entry", "session_input", "session_task_link", "session")) {
                try {
                    rows += dbc.delete(t, "session_id = ?", arrayOf(id))
                } catch (_: Exception) {
                    // older schema may lack a table
                }
            }
            // the session table keys on id, not session_id
            try {
                rows += dbc.delete("session", "id = ?", arrayOf(id))
            } catch (_: Exception) {
            }
            dbc.setTransactionSuccessful()
        } finally {
            dbc.endTransaction()
            dbc.close()
        }
        return rows
    }

    // ───────────────────────── dsh ─────────────────────────

    fun dshAvailable(ctx: Context): Boolean = DshUsage.sessionsRoot(ctx).isDirectory

    private class DshParsed(
        val id: String, val dir: File, val cwd: String,
        val createdAt: Long, val subagent: Boolean,
        var title: String, var lastAt: Long, var model: String,
        var userMessages: Int, var assistantMessages: Int, var requests: Int,
        var toolCalls: Int, var retries: Int,
        var input: Long, var output: Long, var reasoning: Long, var cache: Long,
        val tools: HashMap<String, IntArray>, // name -> [count, errors]
        val models: HashMap<String, LongArray>, // model -> [reqs, in, out, reasoning]
        val inputs: MutableList<String>,
    )

    private fun dshParseAll(ctx: Context): List<DshParsed> {
        val root = DshUsage.sessionsRoot(ctx)
        if (!root.isDirectory) return emptyList()
        val byDir = LinkedHashMap<File, MutableList<File>>()
        root.walkTopDown().maxDepth(3)
            .filter { it.isFile && it.name.contains("jsonl") }
            .forEach { byDir.getOrPut(it.parentFile) { mutableListOf() }.add(it) }
        val out = mutableListOf<DshParsed>()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        for ((dir, files) in byDir) {
            var parsed: DshParsed? = null
            for (f in files) {
                try {
                    f.inputStream().use { raw ->
                        val input: InputStream =
                            if (f.name.endsWith(".zstd")) ZstdInputStream(raw).apply { setContinuous(true) } else raw
                        BufferedInputStream(input).use { stream ->
                            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                                for (line in lines) {
                                    if (!line.trim().isNotEmpty()) continue
                                    val o = try { JSONObject(line) } catch (_: Exception) { continue }
                                    val t = o.optString("type")
                                    val time = o.optLong("time", 0L)
                                    when (t) {
                                        "session" -> {
                                            parsed = DshParsed(
                                                id = o.optString("id", dir.name), dir = dir,
                                                cwd = o.optString("cwd"), createdAt = o.optLong("createdAt", time),
                                                subagent = o.optString("origin") == "subagent",
                                                title = "", lastAt = time, model = "",
                                                userMessages = 0, assistantMessages = 0, requests = 0,
                                                toolCalls = 0, retries = 0, input = 0, output = 0, reasoning = 0,
                                                cache = 0, tools = HashMap(), models = HashMap(),
                                                inputs = mutableListOf(),
                                            )
                                        }
                                        "session/title" -> parsed?.let {
                                            if (time >= it.lastAt - 1) it.title = o.optJSONObject("data")?.optString("title").orEmpty()
                                        }
                                        "user/message" -> parsed?.let {
                                            it.userMessages++
                                            it.lastAt = maxOf(it.lastAt, time)
                                            if (it.title.isEmpty()) {
                                                val c = o.optJSONObject("data")?.optJSONArray("content")
                                                if (c != null && c.length() > 0) it.title = c.optJSONObject(0)?.optString("text").orEmpty().take(60)
                                            }
                                        }
                                        "assistant/message" -> parsed?.let { s ->
                                            val data = o.optJSONObject("data") ?: return@let
                                            val usage = data.optJSONObject("usage") ?: return@let
                                            s.assistantMessages++
                                            s.requests++
                                            s.lastAt = maxOf(s.lastAt, time)
                                            s.input += usage.optLong("inputTokens", 0)
                                            s.output += usage.optLong("outputTokens", 0)
                                            s.cache += usage.optLong("cacheReadTokens", 0)
                                            s.reasoning += usage.optLong("reasoningTokens", 0)
                                            val src = data.optJSONObject("message")?.optJSONObject("source")
                                            val model = (src?.optString("provider").orEmpty() + "/" + src?.optString("model").orEmpty().ifEmpty { "?" })
                                            s.model = model
                                            val m = s.models.getOrPut(model) { LongArray(4) }
                                            m[0]++; m[1] += usage.optLong("inputTokens", 0); m[2] += usage.optLong("outputTokens", 0); m[3] += usage.optLong("reasoningTokens", 0)
                                        }
                                        "tool/call" -> parsed?.let { s ->
                                            val data = o.optJSONObject("data") ?: return@let
                                            val name = data.optString("name", "?")
                                            s.toolCalls++
                                            s.lastAt = maxOf(s.lastAt, time)
                                            val tt = s.tools.getOrPut(name) { IntArray(2) }
                                            tt[0]++
                                        }
                                        "tool/result" -> parsed?.let { s ->
                                            val data = o.optJSONObject("data") ?: return@let
                                            s.lastAt = maxOf(s.lastAt, time)
                                            val callId = data.optJSONObject("message")?.optString("id").orEmpty()
                                            val name = data.optString("name", "")
                                            // count errors: result content carries isError
                                            val msg = data.optJSONObject("message")
                                            val contents = msg?.optJSONArray("content")
                                            var isError = false
                                            if (contents != null) {
                                                for (i in 0 until contents.length()) {
                                                    val ct = contents.optJSONObject(i)?.optJSONArray("content") ?: continue
                                                    for (j in 0 until ct.length()) {
                                                        if (ct.optJSONObject(j)?.optBoolean("isError") == true) isError = true
                                                    }
                                                }
                                            }
                                            if (isError) {
                                                val key = if (name.isNotEmpty()) name else "未知工具"
                                                val tt = s.tools.getOrPut(key) { IntArray(2) }
                                                tt[1]++
                                            }
                                        }
                                        "llm/retry" -> parsed?.let { s ->
                                            s.retries++
                                            s.lastAt = maxOf(s.lastAt, time)
                                        }
                                        "turn/start" -> parsed?.let { s -> s.lastAt = maxOf(s.lastAt, time) }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            parsed?.let { p ->
                if (p.title.isEmpty()) p.title = p.id.take(12)
                out += p
            }
        }
        return out
    }

    private fun toSession(p: DshParsed): AgentSession = AgentSession(
        agent = "dsh", id = p.id, title = p.title.ifEmpty { p.id.take(12) },
        directory = p.cwd, createdAt = p.createdAt, updatedAt = p.lastAt,
        requests = p.requests, userMessages = p.userMessages, assistantMessages = p.assistantMessages,
        toolCalls = p.toolCalls, inputTokens = p.input, outputTokens = p.output,
        reasoningTokens = p.reasoning, cacheTokens = p.cache,
        durationMs = (p.lastAt - p.createdAt).coerceAtLeast(0), retries = p.retries,
        subagent = p.subagent, model = p.model,
    )

    fun dshList(ctx: Context): List<AgentSession> =
        dshParseAll(ctx).map(::toSession).sortedByDescending { it.updatedAt }

    fun dshDetail(ctx: Context, id: String): SessionDetail {
        val p = dshParseAll(ctx).firstOrNull { it.id == id }
            ?: return SessionDetail(AgentSession("dsh", id, id, "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, ""), emptyList(), emptyList(), emptyList())
        val models = p.models.entries.map { (m, v) -> ModelRow(m, v[0].toInt(), v[1], v[2], v[3]) }.sortedByDescending { it.input }
        val tools = p.tools.entries.map { (n, v) -> ToolRow(n, v[0], 0, v[1]) }.sortedByDescending { it.count }
        return SessionDetail(toSession(p), models, tools, p.inputs.toList())
    }

    /** dsh sessions live in their own directory — removal is a recursive delete. */
    fun dshDelete(ctx: Context, id: String): Boolean {
        val p = dshParseAll(ctx).firstOrNull { it.id == id } ?: return false
        return p.dir.deleteRecursively()
    }

    // ───────────────────────── opencode ─────────────────────────

    private fun opencodeDb(ctx: Context): File =
        File(NodeRuntime.homeDir(ctx), ".local/share/opencode/opencode.db")

    fun opencodeAvailable(ctx: Context): Boolean = opencodeDb(ctx).exists()

    fun opencodeList(ctx: Context): List<AgentSession> {
        val f = opencodeDb(ctx)
        if (!f.exists()) return emptyList()
        val out = mutableListOf<AgentSession>()
        return try {
            openReadOnly(ctx, f) { dbc ->
                dbc.rawQuery(
                    "SELECT id, COALESCE(title, id), COALESCE(directory, path, ''), time_created, time_updated," +
                        " COALESCE(tokens_input,0), COALESCE(tokens_output,0), COALESCE(tokens_reasoning,0)," +
                        " COALESCE(tokens_cache_read,0) + COALESCE(tokens_cache_write,0)," +
                        " COALESCE(model, ''), COALESCE(cost, 0)" +
                        " FROM session ORDER BY time_updated DESC",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        // message counts come from the message table (role in data JSON)
                        out += AgentSession(
                            agent = "opencode", id = c.getString(0), title = c.getString(1), directory = c.getString(2),
                            createdAt = c.getLong(3), updatedAt = c.getLong(4),
                            requests = 0, userMessages = 0, assistantMessages = 0,
                            toolCalls = 0, inputTokens = c.getLong(5), outputTokens = c.getLong(6),
                            reasoningTokens = c.getLong(7), cacheTokens = c.getLong(8),
                            durationMs = 0, retries = 0, subagent = false,
                            model = c.getString(9) + (if (c.getDouble(10) > 0) "  $${"%.2f".format(c.getDouble(10))}" else ""),
                        )
                    }
                }
                // fill message counts in Kotlin (role sits inside a JSON blob)
                val roles = HashMap<String, Pair<Int, Int>>()
                dbc.rawQuery("SELECT session_id, data FROM message", null).use { c ->
                    while (c.moveToNext()) {
                        try {
                            val role = JSONObject(c.getString(1)).optString("role")
                            val pair = roles.getOrPut(c.getString(0)) { Pair(0, 0) }
                            if (role == "user") roles[c.getString(0)] = Pair(pair.first + 1, pair.second)
                            else if (role == "assistant") roles[c.getString(0)] = Pair(pair.first, pair.second + 1)
                        } catch (_: Exception) {
                        }
                    }
                }
                out.replaceAll { s ->
                    val (u, a) = roles[s.id] ?: Pair(0, 0)
                    s.copy(userMessages = u, assistantMessages = a, requests = u.coerceAtLeast(a))
                }
            }
            out
        } catch (_: Exception) {
            out
        }
    }

    fun opencodeDetail(ctx: Context, id: String): SessionDetail {
        val sess = opencodeList(ctx).firstOrNull { it.id == id }
            ?: return SessionDetail(AgentSession("opencode", id, id, "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, ""), emptyList(), emptyList(), emptyList())
        val tools = mutableListOf<ToolRow>()
        try {
            openReadOnly(ctx, opencodeDb(ctx)) { dbc ->
                // tool invocations live in `part` rows typed "tool"; the table may
                // be absent on older schemas — tolerate and degrade
                dbc.rawQuery(
                    "SELECT COALESCE(json_extract(data, '$.tool'), '未知工具') AS tool, COUNT(*)" +
                        " FROM part WHERE session_id = ? AND json_extract(data, '$.type') = 'tool'" +
                        " GROUP BY tool ORDER BY 2 DESC",
                    arrayOf(id),
                ).use { c ->
                    while (c.moveToNext()) tools += ToolRow(c.getString(0), c.getInt(1), 0, 0)
                }
            }
        } catch (_: Exception) {
        }
        return SessionDetail(sess, emptyList(), tools, emptyList())
    }

    // ───────────────────────── shared ─────────────────────────

    private inline fun openReadOnly(ctx: Context, f: File, block: (SQLiteDatabase) -> Unit) {
        // snapshot db + wal into cache so the live CLI file is never touched
        val tmp = File(ctx.cacheDir, "session-copy.sqlite")
        f.inputStream().use { it.copyTo(tmp.outputStream()) }
        val wal = File(f.parentFile, f.name + "-wal")
        val tmpWal = File(ctx.cacheDir, "session-copy.sqlite-wal")
        if (wal.exists()) wal.inputStream().use { it.copyTo(tmpWal.outputStream()) } else tmpWal.delete()
        val dbc = SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            block(dbc)
        } finally {
            dbc.close()
            tmp.delete()
            tmpWal.delete()
        }
    }
}
