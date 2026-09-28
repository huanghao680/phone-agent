package com.phoneagent

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.concurrent.thread

/**
 * "Zcode" launcher icon target: fullscreen terminal running the embedded Node
 * runtime and the zcode TUI via a real PTY (Termux terminal-emulator).
 */
class ZcodeTerminalActivity : ComponentActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var loading: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // picker passes the choice via extra; fall back to the holder so a
        // relaunch from recents keeps the original agent
        intent?.getStringExtra("agent")?.let { SessionHolder.pendingAgent = it }
        setContentView(R.layout.activity_zcode)
        terminalView = findViewById(R.id.terminal)
        loading = findViewById(R.id.loading)
        terminalView.setTerminalViewClient(viewClient)
        // TerminalRenderer is only created here (the view does not do it in its
        // constructor); without this the first onSizeChanged NPEs on mRenderer.
        val textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        terminalView.setTextSize(textSize.toInt())
        title = when (SessionHolder.pendingAgent) {
            "dsh" -> "DeepSeek Harness TUI"
            else -> "Zcode TUI"
        }

        val existing = SessionHolder.current
        val existingMatches = existing != null && existing.isRunning() &&
            SessionHolder.pendingAgent == SessionHolder.currentAgent
        if (existingMatches) {
            loading.visibility = View.GONE
            terminalView.visibility = View.VISIBLE
            terminalView.requestFocus();
        terminalView.attachSession(existing)
        } else {
            thread(name = "runtime-extract") {
                if (!NodeRuntime.isRuntimeExtracted(this)) {
                    NodeRuntime.extractRuntime(this)
                }
                // the terminal bootstrap script installs from these tarballs
                NodeRuntime.copyPackages(this)
                // Android 11+ blocks link(); seed config via rename so the CLI
                // never has to create it with a hard link
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
        val agent = SessionHolder.pendingAgent
        val script = when (agent) {
            "dsh" -> SetupScripts.dshTuiScript(this)
            else -> SetupScripts.zcodeScript(this)
        }
        val env = NodeRuntime.environment(this)
            .map { (k, v) -> "$k=$v" }
            .toTypedArray()
        val workDir = StorageAccess.defaultWorkspace(this)
        val session = TerminalSession(
            "/system/bin/sh",
            workDir,
            arrayOf("/system/bin/sh", script.absolutePath),
            env,
            null,
            sessionClient,
        )
        SessionHolder.current = session
        SessionHolder.currentAgent = SessionHolder.pendingAgent
        terminalView.requestFocus();
        terminalView.attachSession(session)
        val svcIntent = Intent(this, SessionService::class.java).putExtra("agent", SessionHolder.pendingAgent)
        startForegroundService(svcIntent)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Workspace.REQUEST_PICK && resultCode == RESULT_OK) {
            data?.data?.let { Workspace.persist(this, it) }
            startSession()
        }
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            runOnUiThread { if (::terminalView.isInitialized) terminalView.onScreenUpdated() }
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            runOnUiThread { title = changedSession.title ?: getString(R.string.zcode_label) }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            runOnUiThread {
                val agent = when (SessionHolder.pendingAgent) {
                    "dsh" -> "DeepSeek Harness"
                    else -> "Zcode"
                }
                Notifications.sessionFinished(this@ZcodeTerminalActivity, agent, finishedSession.getExitStatus())
                Toast.makeText(this@ZcodeTerminalActivity, "$agent 会话已结束（退出码 ${finishedSession.getExitStatus()}）", Toast.LENGTH_LONG).show()
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

        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale

        override fun onSingleTapUp(e: MotionEvent?) {
            terminalView.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(terminalView, 0)
        }

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

    override fun onDestroy() {
        // Session intentionally survives activity teardown (SessionHolder) so the
        // TUI can be resumed from the launcher icon.
        super.onDestroy()
    }
}

/** Keeps the terminal session alive across activity restarts. */
object SessionHolder {
    @Volatile var current: TerminalSession? = null

    /** Which agent TUI the user picked in TerminalPickerActivity ("zcode" | "dsh"). */
    @Volatile var pendingAgent: String = "zcode"

    /** Agent the current session was started with, so a mismatched pick restarts it. */
    @Volatile var currentAgent: String = "zcode"

    /** Live sessions for the standalone binary TUIs, keyed by agent name. */
    @Volatile var binarySessions: MutableMap<String, TerminalSession> =
        java.util.concurrent.ConcurrentHashMap()
}
