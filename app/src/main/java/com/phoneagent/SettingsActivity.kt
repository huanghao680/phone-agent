package com.phoneagent

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        status = findViewById(R.id.status)

        val keyInput = findViewById<EditText>(R.id.key_input)
        val registryInput = findViewById<EditText>(R.id.registry_input)
        val proxyInput = findViewById<EditText>(R.id.proxy_input)
        keyInput.setText(Prefs.deepseekKey(this))
        registryInput.setText(Prefs.npmRegistry(this))
        proxyInput.setText(Prefs.httpProxy(this))

        keyInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) Prefs.setDeepseekKey(this, keyInput.text.toString().trim())
        }
        registryInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) Prefs.setNpmRegistry(this, registryInput.text.toString().trim())
        }
        proxyInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) Prefs.setHttpProxy(this, proxyInput.text.toString().trim())
        }

        val workspaceText = findViewById<TextView>(R.id.workspace_text)
        fun refreshWorkspace() {
            val path = Workspace.workspacePath(this)
            workspaceText.text = when {
                path == null -> "未选择（默认使用 APP 私有目录）"
                Workspace.isGrantValid(this) -> path
                else -> "$path（授权已失效，请重新选择）"
            }
        }
        refreshWorkspace()
        findViewById<Button>(R.id.pick_workspace_btn).setOnClickListener {
            Workspace.pick(this)
        }
        findViewById<Button>(R.id.clear_workspace_btn).setOnClickListener {
            Workspace.clear(this)
            refreshWorkspace()
        }

        findViewById<Button>(R.id.install_module_btn).setOnClickListener {
            confirm(
                "安装系统级命令？",
                "将在 ${RootIntegration.MODULE_DIR} 安装 Magisk 模块（需复制约 150MB 运行时），重启后生效。",
            ) { doRoot { RootIntegration.installModule(this) } }
        }
        findViewById<Button>(R.id.uninstall_module_btn).setOnClickListener {
            confirm("卸载系统级命令？", "将删除已安装的 Magisk 模块，重启后 zcode / dsh 系统命令失效。") { doRoot { RootIntegration.uninstallModule() } }
        }
        findViewById<Button>(R.id.phantom_btn).setOnClickListener {
            confirm("修复幻象进程限制？", "将放宽系统对后台子进程的清理策略，避免终端会话被杀。") { doRoot { RootIntegration.fixPhantomProcesses() } }
        }
        findViewById<Button>(R.id.reinit_btn).setOnClickListener {
            confirm("重新初始化运行时？", "将删除已解压的 Node 运行时和已安装组件，下次打开 APP 时重新解压安装。") {
                NodeRuntime.usrDir(this).deleteRecursively()
                NodeRuntime.pkgDir(this).deleteRecursively()
                NodeRuntime.homeDir(this).deleteRecursively()
                filesDir.listFiles()?.filter { it.name.startsWith(".runtime-") }?.forEach { it.delete() }
                Toast.makeText(this, "已重置，重新打开 Zcode / DeepSeek Harness 即可重建", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Workspace.REQUEST_PICK && resultCode == RESULT_OK) {
            data?.data?.let { Workspace.persist(this, it) }
            recreate()
        }
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> action() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doRoot(block: () -> List<String>) {
        status.text = "执行中…"
        thread {
            val lines = try {
                block()
            } catch (e: Exception) {
                listOf("异常：${e.message}")
            }
            runOnUiThread { status.text = lines.joinToString("\n") }
        }
    }
}
