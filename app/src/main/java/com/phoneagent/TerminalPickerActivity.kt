package com.phoneagent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * Terminal entry point: pick which agent TUI to run in the shared terminal.
 *
 * Zcode and dsh run on the embedded Node; opencode ships glibc-compiled Bun
 * binaries with no bionic build, so it is listed with an honest "unsupported
 * on Android without Termux" explanation instead of failing mid-session.
 */
class TerminalPickerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal_picker)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.title = getString(R.string.terminal_label)

        val root = findViewById<LinearLayout>(R.id.picker_root)
        val choices = listOf(
            TerminalChoice("zcode", "Zcode TUI", "Z.ai 的编码 Agent（内嵌 Node，自动安装）", true),
            TerminalChoice("dsh", "DeepSeek Harness TUI", "DeepSeek 官方 Harness（内嵌 Node，自动安装）", true),
            TerminalChoice(
                "opencode",
                "opencode TUI",
                "SST 的开源 Agent —— 其官方二进制为 glibc 构建，安卓（bionic）暂不兼容，开发中",
                false,
            ),
        )
        for (c in choices) {
            val v = layoutInflater.inflate(R.layout.item_terminal_choice, root, false)
            v.findViewById<TextView>(R.id.choice_title).text = c.title
            v.findViewById<TextView>(R.id.choice_desc).text = c.desc
            v.findViewById<Button>(R.id.choice_btn).isEnabled = c.available
            v.findViewById<Button>(R.id.choice_btn).setOnClickListener { onPick(c.id) }
            root.addView(v)
        }
    }

    private fun onPick(id: String) {
        when (id) {
            "zcode", "dsh" -> {
                SessionHolder.pendingAgent = id
                startActivity(Intent(this, ZcodeTerminalActivity::class.java))
            }
            "opencode" -> AlertDialog.Builder(this)
                .setTitle("opencode")
                .setMessage("opencode 官方发布的是 glibc 编译的 Bun 二进制，当前安卓（bionic libc）无法直接运行。后续版本计划通过自编译 bionic 目标支持。")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private data class TerminalChoice(val id: String, val title: String, val desc: String, val available: Boolean)
}
