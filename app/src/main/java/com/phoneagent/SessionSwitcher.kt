package com.phoneagent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Live-session switcher shared by the terminal hosts. The terminal layer keeps
 * one PTY session per agent alive in the background (SessionHolder.nodeSessions
 * for the Node CLIs, binarySessions for the standalone binaries) but until now
 * the only way back was the launcher icon of that agent. This lists every live
 * TUI across both maps and jumps to it, so concurrent sessions are actually
 * reachable — the roadmap's "多会话 UI" item.
 */
object SessionSwitcher {

    /** Wires the floating ⇄ button of a terminal host to the switcher dialog. */
    fun attach(activity: Activity, button: View) {
        button.setOnClickListener { show(activity) }
    }

    fun show(activity: Activity) {
        val live = liveAgents()
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        var dlg: AlertDialog? = null
        if (live.isEmpty()) {
            list.addView(label(ctx, "当前没有活跃的 TUI 会话\n打开一个 agent 卡片即启动", "#8B949E"))
        }
        for (id in live) {
            val row = label(ctx, "●  " + AgentRegistry.label(id) + " TUI", "#7CC4FF")
            row.setOnClickListener {
                dlg?.dismiss()
                launch(ctx, id)
            }
            list.addView(row)
        }
        dlg = AlertDialog.Builder(ctx)
            .setTitle("切换 TUI 会话")
            .setView(list)
            .setNegativeButton("关闭", null)
            .show()
    }

    /** Canonical ids of agents that currently hold a live TUI session. */
    fun liveAgents(): List<String> {
        val out = mutableListOf<String>()
        for (id in listOf("zcode", "dsh")) {
            if (SessionHolder.nodeSessions[id]?.isRunning() == true) out += id
        }
        for ((key, s) in SessionHolder.binarySessions) {
            if (!s.isRunning()) continue
            val id = when (key) {
                "Codex" -> "codex"
                "Claude Code" -> "claude"
                else -> "opencode"
            }
            if (id !in out) out += id
        }
        return out
    }

    /** Same mapping the terminal picker uses. */
    fun launch(ctx: Context, id: String) {
        val intent = when (id) {
            "zcode" -> Intent(ctx, ZcodeTerminalActivity::class.java).putExtra("agent", "zcode")
            "dsh" -> Intent(ctx, ZcodeTerminalActivity::class.java).putExtra("agent", "dsh")
            "codex" -> Intent(ctx, CodexTerminalActivity::class.java)
            "claude" -> Intent(ctx, ClaudeTerminalActivity::class.java)
            "opencode" -> Intent(ctx, OpencodeTerminalActivity::class.java)
            else -> return
        }
        ctx.startActivity(intent)
    }

    private fun label(ctx: Context, text: String, colorHex: String): TextView {
        val density = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            this.text = text
            setTextColor(Color.parseColor(colorHex))
            textSize = 15f
            val pad = (10 * density).toInt()
            setPadding(0, pad / 2, 0, pad / 2)
        }
    }
}
