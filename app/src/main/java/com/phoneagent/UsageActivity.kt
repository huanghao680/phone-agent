package com.phoneagent

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * Usage stats read from the embedded zcode CLI's own SQLite database
 * (~/.zcode/cli/db/db.sqlite, table `message`: token columns per assistant
 * turn). Opened from the terminal picker's stats row. Opened read-only.
 */
class UsageActivity : AppCompatActivity() {

    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usage)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.title = getString(R.string.usage_title)
        out = findViewById(R.id.usage_text)
        thread(name = "usage-query") {
            val text = query()
            runOnUiThread { out.text = text }
            findViewById<View>(R.id.loading).visibility = View.GONE
        }
    }

    private fun db(): File = File(NodeRuntime.homeDir(this), ".zcode/cli/db/db.sqlite")

    private fun query(): String {
        val f = db()
        if (!f.exists()) return getString(R.string.usage_empty)
        return try {
            // SQLite WAL: copy db + wal to cache so the live CLI file isn't touched
            val tmp = File(cacheDir, "usage-copy.sqlite")
            val wal = File(f.parentFile, "db.sqlite-wal")
            f.inputStream().use { it.copyTo(tmp.outputStream()) }
            if (wal.exists()) {
                wal.inputStream().use { it.copyTo(File(cacheDir, "usage-copy.sqlite-wal").outputStream()) }
            }
            val dbc = SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val sb = StringBuilder()

            // per-day aggregates from the model_usage table (one row per model
            // request; columns are flat integers — no JSON extraction needed)
            val daily = mutableMapOf<String, LongArray>() // [in, out, reasoning, requests]
            dbc.rawQuery(
                "SELECT date(started_at/1000,'unixepoch','localtime') AS d," +
                    " SUM(input_tokens), SUM(output_tokens), SUM(reasoning_tokens), COUNT(*)" +
                    " FROM model_usage GROUP BY d ORDER BY d DESC LIMIT 14",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val day = c.getString(0) ?: "?"
                    daily[day] = longArrayOf(c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4))
                }
            }
            var totalIn = 0L
            var totalOut = 0L
            var totalRe = 0L
            var totalReq = 0L
            sb.append("近 14 天用量\n──────────────\n")
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

            // per-model breakdown
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
