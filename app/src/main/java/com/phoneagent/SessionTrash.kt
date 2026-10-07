package com.phoneagent

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Recycle bin for agent sessions. A delete moves the session's full data into an
 * app-private trash directory instead of destroying it; restore puts it back into
 * the live store, purge is final.
 *
 * Payload per item: `files/trash/<agent>/<deletedAt>-<id>/`
 *  - zcode / opencode: `<agent>.sqlite` — every session-owned table copied with
 *    `CREATE TABLE AS SELECT` (schema + rows in one statement, so live-schema
 *    drift cannot break the export). Restore re-inserts with INSERT OR REPLACE.
 *  - dsh: the session directory moved here unchanged; meta records its original
 *    path under ~/.dsh/sessions so restore can move it back.
 */
object SessionTrash {

    data class Item(
        val dir: File,
        val agent: String,
        val id: String,
        val title: String,
        val deletedAt: Long,
        val bytes: Long,
    )

    fun trashDir(ctx: Context): File = File(ctx.filesDir, "trash")

    fun list(ctx: Context): List<Item> {
        val root = trashDir(ctx)
        val out = mutableListOf<Item>()
        val agents = root.listFiles() ?: return out
        for (a in agents) {
            for (d in a.listFiles() ?: emptyArray()) {
                val meta = File(d, "meta.json")
                if (!meta.isFile) continue
                try {
                    val o = JSONObject(meta.readText())
                    out += Item(
                        dir = d,
                        agent = o.optString("agent"),
                        id = o.optString("id"),
                        title = o.optString("title"),
                        deletedAt = o.optLong("deletedAt"),
                        bytes = d.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    )
                } catch (_: Exception) {
                }
            }
        }
        return out.sortedByDescending { it.deletedAt }
    }

    fun itemCount(ctx: Context): Int = list(ctx).size

    /**
     * Backs the session up into trash, then hard-deletes it from the live store.
     * Returns false (leaving everything untouched) when the backup fails.
     */
    fun moveToTrash(ctx: Context, agent: String, id: String): Boolean {
        // capture the display title first — the hard delete below removes the
        // session from every list this could otherwise look it up in
        val title = try {
            when (agent) {
                "zcode" -> SessionData.zcodeList(ctx).firstOrNull { it.id == id }?.title
                "opencode" -> SessionData.opencodeList(ctx).firstOrNull { it.id == id }?.title
                else -> SessionData.dshList(ctx).firstOrNull { it.id == id }?.title
            } ?: id
        } catch (_: Exception) {
            id
        }
        val dir = File(File(trashDir(ctx), agent), "${System.currentTimeMillis()}-$id")
        dir.deleteRecursively()
        dir.mkdirs()
        val ok = try {
            when (agent) {
                "zcode" -> backupSqlite(ctx, "zcode", id, File(dir, "zcode.sqlite")) && SessionData.zcodeDelete(ctx, id) > 0
                "opencode" -> backupSqlite(ctx, "opencode", id, File(dir, "opencode.sqlite")) && SessionData.opencodeDelete(ctx, id) > 0
                else -> backupDsh(ctx, id, dir)
            }
        } catch (e: Exception) {
            Log.w("SessionTrash", "moveToTrash $agent/$id failed", e)
            false
        }
        if (!ok) {
            dir.deleteRecursively()
            return false
        }
        // merge — the dsh backup already recorded its original relative path here
        val meta = File(dir, "meta.json")
        val o = if (meta.isFile) try { JSONObject(meta.readText()) } catch (_: Exception) { JSONObject() } else JSONObject()
        o.put("agent", agent).put("id", id).put("title", title).put("deletedAt", System.currentTimeMillis())
        meta.writeText(o.toString())
        return true
    }

    /** Puts the session back into its live store. Returns an error string or null. */
    fun restore(ctx: Context, item: Item): String? = try {
        val err = when (item.agent) {
            "zcode" -> restoreSqlite(ctx, "zcode", item)
            "opencode" -> restoreSqlite(ctx, "opencode", item)
            else -> restoreDsh(ctx, item)
        }
        if (err == null) item.dir.deleteRecursively()
        err
    } catch (e: Exception) {
        e.message ?: "恢复失败"
    }

    fun purge(ctx: Context, item: Item): Boolean = item.dir.deleteRecursively()

    fun emptyAll(ctx: Context): Boolean = trashDir(ctx).deleteRecursively()

    // ───────────────────────── sqlite agents (zcode / opencode) ─────────────────────────

    private fun ownedTables(agent: String): Array<String> = when (agent) {
        "zcode" -> arrayOf("message", "part", "model_usage", "tool_usage", "turn_usage", "input_history", "session_entry", "session_input", "session_task_link")
        else -> arrayOf("part", "message", "todo", "session_input", "session_message", "session_share", "session_context_epoch")
    }

    private fun liveDb(agent: String, ctx: Context): File = when (agent) {
        "zcode" -> File(NodeRuntime.homeDir(ctx), ".zcode/cli/db/db.sqlite")
        else -> File(NodeRuntime.homeDir(ctx), ".local/share/opencode/opencode.db")
    }

    private fun backupSqlite(ctx: Context, agent: String, id: String, out: File): Boolean {
        val live = liveDb(agent, ctx)
        if (!live.exists()) return false
        out.parentFile?.mkdirs()
        // SQLite's ATTACH refuses to CREATE a fresh file here (SQLITE_CANTOPEN on
        // this platform), so materialize an empty file first
        if (!out.exists()) out.createNewFile()
        val dbc = SQLiteDatabase.openDatabase(live.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            dbc.rawQuery("PRAGMA busy_timeout = 5000", null).use { it.moveToFirst() }
            dbc.execSQL("ATTACH DATABASE '" + out.absolutePath.replace("'", "''") + "' AS trashbak")
            try {
                dbc.beginTransaction()
                try {
                    var any = false
                    for (t in ownedTables(agent)) {
                        try {
                            // one statement copies schema + rows; a missing table just skips
                            dbc.execSQL("CREATE TABLE trashbak.`$t` AS SELECT * FROM main.`$t` WHERE session_id = ?", arrayOf(id))
                            any = true
                        } catch (_: Exception) {
                        }
                    }
                    // both schemas key the session table on id, and both keep an event log keyed by aggregate
                    for (stmt in arrayOf(
                        "CREATE TABLE trashbak.`session` AS SELECT * FROM main.`session` WHERE id = ?",
                        "CREATE TABLE trashbak.`event` AS SELECT * FROM main.`event` WHERE aggregate_id = ?",
                        "CREATE TABLE trashbak.`event_sequence` AS SELECT * FROM main.`event_sequence` WHERE aggregate_id = ?",
                    )) {
                        try {
                            dbc.execSQL(stmt, arrayOf(id))
                            any = true
                        } catch (_: Exception) {
                        }
                    }
                    if (!any) return false
                    dbc.setTransactionSuccessful()
                } finally {
                    dbc.endTransaction()
                }
            } finally {
                dbc.execSQL("DETACH DATABASE trashbak")
            }
        } finally {
            dbc.close()
        }
        return out.length() > 0
    }

    private fun restoreSqlite(ctx: Context, agent: String, item: Item): String? {
        val bak = File(item.dir, "$agent.sqlite")
        if (!bak.isFile) return "回收站备份缺失"
        val live = liveDb(agent, ctx)
        live.parentFile?.mkdirs()
        val dbc = SQLiteDatabase.openDatabase(live.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            dbc.rawQuery("PRAGMA busy_timeout = 5000", null).use { it.moveToFirst() }
            dbc.execSQL("ATTACH DATABASE '" + bak.absolutePath.replace("'", "''") + "' AS trashbak")
            val tables = mutableListOf<String>()
            dbc.rawQuery("SELECT name FROM trashbak.sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }
            try {
                dbc.beginTransaction()
                try {
                    for (t in tables) {
                        dbc.execSQL("INSERT OR REPLACE INTO main.`$t` SELECT * FROM trashbak.`$t`")
                    }
                    dbc.setTransactionSuccessful()
                } finally {
                    dbc.endTransaction()
                }
            } catch (e: Exception) {
                return e.message ?: "恢复失败"
            } finally {
                dbc.execSQL("DETACH DATABASE trashbak")
            }
        } finally {
            dbc.close()
        }
        return null
    }

    // ───────────────────────── dsh (directory move) ─────────────────────────

    private fun backupDsh(ctx: Context, id: String, dir: File): Boolean {
        val src = dshSessionDir(ctx, id) ?: return false
        val payload = File(dir, "payload")
        if (!src.renameTo(payload)) {
            // rename can fail across mount points — fall back to copy + delete
            if (!payload.mkdirs()) return false
            if (!src.copyRecursively(payload, overwrite = true)) {
                payload.deleteRecursively()
                return false
            }
            src.deleteRecursively()
        }
        val sessionsRoot = DshUsage.sessionsRoot(ctx)
        val rel = src.relativeToOrNull(sessionsRoot)?.path
            ?: src.absolutePath.removePrefix(sessionsRoot.absolutePath).trimStart('/')
        File(dir, "meta.json").writeText(
            JSONObject()
                .put("agent", "dsh")
                .put("id", id)
                .put("relPath", rel)
                .toString(),
        )
        return true
    }

    private fun restoreDsh(ctx: Context, item: Item): String? {
        val payload = File(item.dir, "payload")
        if (!payload.isDirectory) return "回收站备份缺失"
        val meta = File(item.dir, "meta.json")
        val rel = if (meta.isFile) JSONObject(meta.readText()).optString("relPath") else ""
        if (rel.isEmpty()) return "原始路径缺失"
        val dest = File(DshUsage.sessionsRoot(ctx), rel)
        if (dest.exists()) return "同名会话已存在于原位置"
        dest.parentFile?.mkdirs()
        return if (payload.renameTo(dest) || payload.copyRecursively(dest, overwrite = true)) {
            payload.deleteRecursively()
            null
        } else {
            "移回原位置失败"
        }
    }

    private fun dshSessionDir(ctx: Context, id: String): File? {
        val root = DshUsage.sessionsRoot(ctx)
        if (!root.isDirectory) return null
        return root.walkTopDown().maxDepth(2).filter { it.isDirectory && it.name == id }.firstOrNull()
    }
}
