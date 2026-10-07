package com.phoneagent

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Cross-agent session migration. All three stores use different data models, so a
 * move is a transcript-level translation: user + assistant text messages carry over
 * verbatim, tool invocations become annotated text lines, token usage rides along
 * where the source has it per message. The result is written in the target agent's
 * native storage (SQLite rows for zcode/opencode, JSONL event stream for dsh) and
 * shows up in that agent's own session list.
 */
object SessionMigrate {

    class Msg(
        val role: String, // user | assistant
        val text: String,
        val time: Long,
        val provider: String = "",
        val model: String = "",
        val input: Long = 0,
        val output: Long = 0,
        val reasoning: Long = 0,
        val cache: Long = 0,
        val turnNo: Long = 0,
    )

    class Transcript(
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val directory: String,
        val msgs: List<Msg>,
    )

    /** Extracts a session as a transcript, then writes it into the target agent's store. */
    fun migrate(ctx: Context, from: String, id: String, to: String): Result<String> {
        return try {
            val t = when (from) {
                "zcode" -> fromZcode(ctx, id)
                "dsh" -> fromDsh(ctx, id)
                else -> fromOpencode(ctx, id)
            } ?: return Result.failure(Exception("源会话不存在"))
            if (t.msgs.isEmpty()) return Result.failure(Exception("该会话没有可迁移的对话内容"))
            val newId = when (to) {
                "zcode" -> toZcode(ctx, t)
                "dsh" -> toDsh(ctx, t)
                else -> toOpencode(ctx, t)
            }
            Result.success(newId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ───────────────────────── shared helpers ─────────────────────────

    /** db + wal snapshot copy so live stores are never touched while reading. */
    private fun snapshot(ctx: Context, live: File, name: String): SQLiteDatabase {
        val tmp = File(ctx.cacheDir, name)
        live.inputStream().use { it.copyTo(tmp.outputStream()) }
        val wal = File(live.parentFile, live.name + "-wal")
        val tmpWal = File(ctx.cacheDir, "$name-wal")
        if (wal.exists()) wal.inputStream().use { it.copyTo(tmpWal.outputStream()) } else tmpWal.delete()
        return SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun toolBrief(input: JSONObject?): String {
        input ?: return ""
        for (k in arrayOf("command", "file_path", "path", "pattern", "url", "query")) {
            val v = input.optString(k)
            if (v.isNotEmpty()) return v.replace('\n', ' ').take(160)
        }
        return ""
    }

    private fun toolLine(name: String, brief: String): String =
        "[工具调用] $name${if (brief.isNotEmpty()) "：$brief" else ""}"

    // ───────────────────────── extractors ─────────────────────────

    private fun fromZcode(ctx: Context, id: String): Transcript? {
        val f = File(NodeRuntime.homeDir(ctx), ".zcode/cli/db/db.sqlite")
        if (!f.exists()) return null
        val dbc = snapshot(ctx, f, "mig-copy.sqlite")
        try {
            val head = dbc.rawQuery(
                "SELECT title, time_created, time_updated, directory FROM session WHERE id = ?", arrayOf(id),
            ).use { c -> if (c.moveToFirst()) Meta(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3)) else null }
                ?: return null
            val msgs = mutableListOf<Msg>()
            dbc.rawQuery(
                "SELECT id, data, time_created FROM message WHERE session_id = ? ORDER BY sequence, time_created", arrayOf(id),
            ).use { mc ->
                while (mc.moveToNext()) {
                    val mid = mc.getString(0)
                    val data = JSONObject(mc.getString(1))
                    val time = data.optJSONObject("time")?.optLong("created") ?: mc.getLong(2)
                    val sb = StringBuilder()
                    dbc.rawQuery("SELECT data FROM part WHERE message_id = ? ORDER BY sequence, time_created", arrayOf(mid)).use { pc ->
                        while (pc.moveToNext()) {
                            val p = JSONObject(pc.getString(0))
                            when (p.optString("type")) {
                                "text" -> sb.append(p.optString("text")).append('\n')
                                "tool" -> sb.append(toolLine(p.optString("tool", "工具"), toolBrief(p.optJSONObject("state")?.optJSONObject("input")))).append('\n')
                            }
                        }
                    }
                    val tok = data.optJSONObject("tokens")
                    msgs += Msg(
                        role = data.optString("role"), text = sb.toString().trim(), time = time,
                        provider = data.optString("providerId"), model = data.optString("modelId"),
                        input = tok?.optLong("input") ?: 0, output = tok?.optLong("output") ?: 0,
                        reasoning = tok?.optLong("reasoning") ?: 0, cache = tok?.optJSONObject("cache")?.optLong("read") ?: 0,
                    )
                }
            }
            return Transcript(head.title, head.created, head.updated, head.directory, msgs.filter { it.text.isNotEmpty() })
        } finally {
            dbc.close()
            File(ctx.cacheDir, "mig-copy.sqlite").delete()
            File(ctx.cacheDir, "mig-copy.sqlite-wal").delete()
        }
    }

    private fun fromOpencode(ctx: Context, id: String): Transcript? {
        val f = File(NodeRuntime.homeDir(ctx), ".local/share/opencode/opencode.db")
        if (!f.exists()) return null
        val dbc = snapshot(ctx, f, "mig-copy.sqlite")
        try {
            val head = dbc.rawQuery(
                "SELECT title, time_created, time_updated, directory FROM session WHERE id = ?", arrayOf(id),
            ).use { c -> if (c.moveToFirst()) Meta(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3)) else null }
                ?: return null
            val msgs = mutableListOf<Msg>()
            dbc.rawQuery(
                "SELECT id, data, time_created FROM message WHERE session_id = ? ORDER BY time_created", arrayOf(id),
            ).use { mc ->
                while (mc.moveToNext()) {
                    val mid = mc.getString(0)
                    val data = JSONObject(mc.getString(1))
                    val time = data.optJSONObject("time")?.optLong("created") ?: mc.getLong(2)
                    val sb = StringBuilder()
                    dbc.rawQuery("SELECT data FROM part WHERE message_id = ? ORDER BY time_created", arrayOf(mid)).use { pc ->
                        while (pc.moveToNext()) {
                            val p = JSONObject(pc.getString(0))
                            when (p.optString("type")) {
                                "text" -> sb.append(p.optString("text")).append('\n')
                                "tool" -> sb.append(toolLine(p.optString("tool", "工具"), toolBrief(p.optJSONObject("state")?.optJSONObject("input")))).append('\n')
                            }
                        }
                    }
                    msgs += Msg(role = data.optString("role"), text = sb.toString().trim(), time = time)
                }
            }
            return Transcript(head.title, head.created, head.updated, head.directory, msgs.filter { it.text.isNotEmpty() })
        } finally {
            dbc.close()
            File(ctx.cacheDir, "mig-copy.sqlite").delete()
            File(ctx.cacheDir, "mig-copy.sqlite-wal").delete()
        }
    }

    private class Meta(val title: String, val created: Long, val updated: Long, val directory: String)

    private fun fromDsh(ctx: Context, id: String): Transcript? {
        val root = DshUsage.sessionsRoot(ctx)
        if (!root.isDirectory) return null
        // only files inside this session's own directory (sessions/<slug>/<id>/)
        val files = root.walkTopDown().maxDepth(3)
            .filter { it.isFile && it.name.contains("jsonl") && it.parentFile?.name == id }
            .toList()
        if (files.isEmpty()) return null
        var cwd = ""
        var createdAt = 0L
        var title = ""
        val msgs = mutableListOf<Msg>()
        for (f in files.sortedBy { it.name }) {
            try {
                f.inputStream().use { raw ->
                    val input: java.io.InputStream =
                        if (f.name.endsWith(".zstd")) com.github.luben.zstd.ZstdInputStream(raw).apply { setContinuous(true) } else raw
                    input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (line in lines) {
                            if (line.isEmpty()) continue
                            val o = try { JSONObject(line) } catch (_: Exception) { continue }
                            when (o.optString("type")) {
                                "session" -> {
                                    cwd = o.optString("cwd")
                                    createdAt = o.optLong("createdAt")
                                }
                                "session/title" -> title = o.optJSONObject("data")?.optString("title") ?: title
                                "user/message" -> {
                                    val data = o.optJSONObject("data") ?: continue
                                    // plugin-injected context snapshots are noise; keep real user input only
                                    if (data.optJSONObject("source")?.optString("kind") != "user") continue
                                    val text = joinContent(data.optJSONArray("content"))
                                    if (text.isNotEmpty()) msgs += Msg("user", text, o.optLong("time"))
                                }
                                "assistant/message" -> {
                                    val data = o.optJSONObject("data") ?: continue
                                    val m = data.optJSONObject("message") ?: continue
                                    val text = joinContent(m.optJSONArray("content"))
                                    val src = m.optJSONObject("source")
                                    val usage = data.optJSONObject("usage")
                                    msgs += Msg(
                                        role = "assistant", text = text, time = o.optLong("time"),
                                        provider = src?.optString("provider") ?: "", model = src?.optString("model") ?: "",
                                        input = usage?.optLong("inputTokens") ?: 0, output = usage?.optLong("outputTokens") ?: 0,
                                        reasoning = usage?.optLong("reasoningTokens") ?: 0, cache = usage?.optLong("cacheReadTokens") ?: 0,
                                        turnNo = data.optLong("turn", 0L),
                                    )
                                }
                                "tool/call" -> {
                                    val data = o.optJSONObject("data") ?: continue
                                    val brief = data.optString("arguments").replace('\n', ' ').take(160)
                                    val line = toolLine(data.optString("name"), brief)
                                    val turn = data.optLong("turn", 0L)
                                    // attach to the assistant message of the same turn, else as a user-side note
                                    val idx = msgs.indexOfLast { it.role == "assistant" && it.turnNo == turn }
                                    if (idx >= 0) {
                                        val prev = msgs[idx]
                                        msgs[idx] = Msg(
                                            prev.role, prev.text + "\n" + line, prev.time, prev.provider, prev.model,
                                            prev.input, prev.output, prev.reasoning, prev.cache, prev.turnNo,
                                        )
                                    } else {
                                        msgs += Msg("assistant", line, o.optLong("time"), turnNo = turn)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (cwd.isEmpty() && createdAt == 0L && msgs.isEmpty()) return null
        val last = msgs.lastOrNull()?.time ?: createdAt
        return Transcript(title.ifEmpty { "dsh 会话 $id" }, createdAt, last, cwd, msgs.filter { it.text.isNotEmpty() })
    }

    private fun joinContent(arr: JSONArray?): String {
        if (arr == null) return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") sb.append(b.optString("text")).append('\n')
        }
        return sb.toString().trim()
    }

    // ───────────────────────── builders ─────────────────────────

    private fun toZcode(ctx: Context, t: Transcript): String {
        val now = System.currentTimeMillis()
        val newId = "sess_" + UUID.randomUUID().toString()
        val dbc = SQLiteDatabase.openDatabase(
            File(NodeRuntime.homeDir(ctx), ".zcode/cli/db/db.sqlite").absolutePath,
            null, SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            dbc.rawQuery("PRAGMA busy_timeout = 5000", null).use { it.moveToFirst() }
            dbc.beginTransaction()
            try {
                val proj = "proj_" + t.directory.trimStart('/').replace('/', '-')
                dbc.execSQL(
                    "INSERT INTO session (id, project_id, slug, directory, path, title, version, permission," +
                        " time_created, time_updated, task_type, title_source, time_title_updated, trace_id)" +
                        " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(
                        newId, proj, newId, t.directory, t.directory, t.title, Versions.ZCODE,
                        // createdAt is the original conversation time; updatedAt is now so the
                        // migrated session surfaces at the top of the target's list
                        "{\"mode\":\"build\"}", t.createdAt, now, "interactive", "first_input", t.createdAt,
                        UUID.randomUUID().toString(),
                    ),
                )
                var seq = 0
                for (m in t.msgs) {
                    val mid = "msg_" + rand(8) + "_" + UUID.randomUUID()
                    val data = JSONObject()
                        .put("role", m.role)
                        .put("time", JSONObject().put("created", m.time))
                        .put("agent", "zcode-agent")
                    if (m.role == "assistant") {
                        if (m.model.isNotEmpty()) data.put("modelId", m.model).put("providerId", m.provider)
                        data.put("finish", "stop")
                        if (m.input > 0 || m.output > 0) {
                            data.put("tokens", JSONObject()
                                .put("total", m.input + m.output)
                                .put("input", m.input).put("output", m.output)
                                .put("reasoning", m.reasoning)
                                .put("cache", JSONObject().put("read", m.cache).put("write", 0)))
                        }
                    }
                    dbc.execSQL(
                        "INSERT INTO message (id, session_id, time_created, time_updated, data, sequence) VALUES (?,?,?,?,?,?)",
                        arrayOf(mid, newId, m.time, m.time, data.toString(), seq),
                    )
                    dbc.execSQL(
                        "INSERT INTO part (id, message_id, session_id, time_created, time_updated, data, sequence) VALUES (?,?,?,?,?,?,?)",
                        arrayOf("part_" + rand(8) + "_" + UUID.randomUUID(), mid, newId, m.time, m.time,
                            JSONObject().put("type", "text").put("text", m.text)
                                .put("time", JSONObject().put("start", m.time).put("end", m.time)).toString(), 0),
                    )
                    // one model_usage row per assistant message so the usage page
                    // and session chips show the migrated conversation's real cost
                    if (m.role == "assistant") {
                        dbc.execSQL(
                            "INSERT INTO model_usage (id, logical_request_id, attempt_index, session_id," +
                                " query_source, provider_id, model_id, status, started_at, completed_at," +
                                " duration_ms, tool_call_count, input_tokens, output_tokens, reasoning_tokens," +
                                " cache_creation_input_tokens, cache_read_input_tokens, computed_total_tokens," +
                                " retry_count, retryable, cancelled_by_user, context_exceeded)" +
                                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            arrayOf(
                                "mu_" + rand(8) + "_" + UUID.randomUUID(), "lr_" + UUID.randomUUID(), 0, newId,
                                "migrated", m.provider.ifEmpty { "migrated" }, m.model.ifEmpty { "unknown" },
                                "completed", m.time, m.time, 0, 0, m.input, m.output, m.reasoning,
                                0, m.cache, m.input + m.output + m.reasoning + m.cache,
                                0, 0, 0, 0,
                            ),
                        )
                    }
                    seq++
                }
                dbc.setTransactionSuccessful()
            } finally {
                dbc.endTransaction()
            }
        } finally {
            dbc.close()
        }
        return newId
    }

    private fun toOpencode(ctx: Context, t: Transcript): String {
        val now = System.currentTimeMillis()
        val newId = "ses_" + rand(22)
        val dbc = SQLiteDatabase.openDatabase(
            File(NodeRuntime.homeDir(ctx), ".local/share/opencode/opencode.db").absolutePath,
            null, SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            dbc.rawQuery("PRAGMA busy_timeout = 5000", null).use { it.moveToFirst() }
            dbc.beginTransaction()
            try {
                var inT = 0L; var outT = 0L; var reT = 0L; var caT = 0L
                for (m in t.msgs) {
                    inT += m.input; outT += m.output; reT += m.reasoning; caT += m.cache
                }
                dbc.execSQL(
                    "INSERT INTO session (id, project_id, slug, directory, path, title, version, cost," +
                        " tokens_input, tokens_output, tokens_reasoning, tokens_cache_read, tokens_cache_write," +
                        " agent, time_created, time_updated) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(
                        newId, "global", "migrated-" + rand(6), t.directory,
                        t.directory.removePrefix("/"), t.title, Versions.OPENCODE_VERSION, 0.0,
                        // same reasoning: original creation time, fresh update stamp
                        inT, outT, reT, caT, 0, "build", t.createdAt, now,
                    ),
                )
                for (m in t.msgs) {
                    val mid = "msg_" + rand(23)
                    val data = JSONObject()
                        .put("role", m.role)
                        .put("time", JSONObject().put("created", m.time))
                        .put("agent", "build")
                    dbc.execSQL(
                        "INSERT INTO message (id, session_id, time_created, time_updated, data) VALUES (?,?,?,?,?)",
                        arrayOf(mid, newId, m.time, m.time, data.toString()),
                    )
                    dbc.execSQL(
                        "INSERT INTO part (id, message_id, session_id, time_created, time_updated, data) VALUES (?,?,?,?,?,?)",
                        arrayOf("prt_" + rand(22), mid, newId, m.time, m.time,
                            JSONObject().put("type", "text").put("text", m.text).toString()),
                    )
                }
                dbc.setTransactionSuccessful()
            } finally {
                dbc.endTransaction()
            }
        } finally {
            dbc.close()
        }
        return newId
    }

    private fun toDsh(ctx: Context, t: Transcript): String {
        val root = DshUsage.sessionsRoot(ctx)
        root.mkdirs()
        // sessions live grouped by workspace slug; reuse the existing one so the
        // migrated session shows up in the same workspace, else derive one
        val slug = root.listFiles()?.firstOrNull { it.isDirectory }?.name
            ?: "-" + t.directory.replace('/', '-') + "-"
        val newId = UUID.randomUUID().toString()
        val dir = File(root, "$slug/$newId")
        dir.mkdirs()
        // plain jsonl is the on-disk form dsh has always been able to read; zstd is
        // only the newer write-side choice
        val out = File(dir, "session.v3.jsonl")
        val lines = mutableListOf<JSONObject>()
        var seq = 0
        var turn = 0
        lines += JSONObject()
            .put("type", "session").put("version", 3).put("id", newId)
            .put("createdAt", if (t.createdAt > 0) t.createdAt else System.currentTimeMillis())
            .put("cwd", t.directory)
        // session/title is not surface-eligible; it carries no surfaceOp
        lines += JSONObject().put("type", "session/title").put("seq", seq++)
            .put("time", t.createdAt).put("data", JSONObject().put("title", t.title))
        for (m in t.msgs) {
            if (m.role == "user") {
                turn++
                // surface-eligible append without a surfaceOp marker is rejected by dsh
                lines += JSONObject()
                    .put("type", "user/message").put("seq", seq++).put("time", m.time)
                    .put("surfaceOp", "append")
                    .put("data", JSONObject()
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", m.text)))
                        .put("source", JSONObject().put("kind", "user").put("rpcId", UUID.randomUUID().toString()))
                        .put("role", "user").put("id", UUID.randomUUID().toString()))
            } else {
                val msg = JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", m.text)))
                    .put("source", JSONObject()
                        .put("kind", "model")
                        .put("provider", m.provider.ifEmpty { "migrated" })
                        .put("model", m.model.ifEmpty { "unknown" })
                        .put("note", "migrated from another agent"))
                    .put("id", UUID.randomUUID().toString())
                val data = JSONObject().put("turn", turn).put("step", 1).put("message", msg)
                if (m.input > 0 || m.output > 0) {
                    data.put("usage", JSONObject()
                        .put("inputTokens", m.input).put("outputTokens", m.output)
                        .put("totalTokens", m.input + m.output)
                        .put("cacheReadTokens", m.cache).put("reasoningTokens", m.reasoning))
                }
                lines += JSONObject().put("type", "assistant/message").put("seq", seq++)
                    .put("time", m.time).put("data", data)
            }
        }
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            for (l in lines) w.write(l.toString() + "\n")
        }
        return newId
    }

    // ───────────────────────── helpers ─────────────────────────

    private const val ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    private fun rand(n: Int): String = buildString {
        repeat(n) { append(ALNUM.random()) }
    }
}
