package com.phoneagent

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import kotlin.concurrent.thread

/** Base for the standalone-TUI activities (codex / claude): shared PTY plumbing. */
abstract class BaseBinaryTuiActivity : ComponentActivity() {

    protected lateinit var terminalView: TerminalView
    protected lateinit var loading: View
    protected abstract val agentName: String
    protected abstract fun scriptFile(): File
    protected abstract fun binaryReady(): Boolean
    protected abstract fun binaryName(): String

    protected lateinit var session: TerminalSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zcode)
        terminalView = findViewById(R.id.terminal)
        loading = findViewById(R.id.loading)
        terminalView.setTerminalViewClient(viewClient)
        val textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        terminalView.setTextSize(textSize.toInt())
        title = agentName

        val existing = SessionHolder.binarySessions[agentName]
        if (existing != null && existing.isRunning()) {
            loading.visibility = View.GONE
            terminalView.visibility = View.VISIBLE
            terminalView.attachSession(existing)
            session = existing
        } else {
            thread(name = "$agentName-extract") {
                if (!NodeRuntime.isRuntimeExtracted(this)) NodeRuntime.extractRuntime(this)
                NodeRuntime.copyPackages(this)
                ZcodeConfig.seed(this)
                runOnUiThread {
                    loading.visibility = View.GONE
                    terminalView.visibility = View.VISIBLE
                    startSession()
                }
            }
        }
    }

    private fun startSession() {
        if (!binaryReady()) {
            Toast.makeText(this, "$agentName 二进制缺失", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val script = scriptFile()
        val env = NodeRuntime.environment(this).map { (k, v) -> "$k=$v" }.toTypedArray()
        val workDir = StorageAccess.defaultWorkspace(this)
        val s = TerminalSession(
            "/system/bin/sh",
            workDir,
            arrayOf("/system/bin/sh", script.absolutePath),
            env,
            null,
            sessionClient,
        )
        SessionHolder.binarySessions[agentName] = s
        session = s
        terminalView.attachSession(s)
        startForegroundService(
            Intent(this, SessionService::class.java).putExtra("agent", agentName)
        )
    }

    protected val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            runOnUiThread { if (::terminalView.isInitialized) terminalView.onScreenUpdated() }
        }
        override fun onTitleChanged(changedSession: TerminalSession) {
            runOnUiThread { title = changedSession.title ?: agentName }
        }
        override fun onSessionFinished(finishedSession: TerminalSession) {
            runOnUiThread {
                Notifications.sessionFinished(this@BaseBinaryTuiActivity, agentName, finishedSession.getExitStatus())
            }
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.text ?: return
            val bytes = clip.toString().toByteArray()
            session.write(bytes, 0, bytes.size)
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {
            runOnUiThread { if (::terminalView.isInitialized) terminalView.onScreenUpdated() }
        }
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = com.termux.terminal.TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    protected val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent?) {}
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }
}

/** Codex CLI terminal (static musl binary). */
class CodexTerminalActivity : BaseBinaryTuiActivity() {
    override val agentName = "Codex"
    override fun scriptFile() = SetupScripts.codexScript(this)
    override fun binaryReady() = BinaryAgents.isReady(this, BinaryAgents.CODEX)
    override fun binaryName() = BinaryAgents.CODEX
}

/** Claude Code terminal (musl binary launched via the bundled loader). */
class ClaudeTerminalActivity : BaseBinaryTuiActivity() {
    override val agentName = "Claude Code"
    override fun scriptFile() = SetupScripts.claudeScript(this)
    override fun binaryReady() = BinaryAgents.isReady(this, BinaryAgents.CLAUDE)
    override fun binaryName() = BinaryAgents.CLAUDE
}
