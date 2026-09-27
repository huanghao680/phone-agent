package com.phoneagent

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
class ZcodeTerminalActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var loading: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zcode)
        terminalView = findViewById(R.id.terminal)
        loading = findViewById(R.id.loading)
        terminalView.setTerminalViewClient(viewClient)
        // TerminalRenderer is only created here (the view does not do it in its
        // constructor); without this the first onSizeChanged NPEs on mRenderer.
        val textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        terminalView.setTextSize(textSize.toInt())

        val existing = SessionHolder.current
        if (existing != null && existing.isRunning()) {
            loading.visibility = View.GONE
            terminalView.visibility = View.VISIBLE
            terminalView.attachSession(existing)
        } else {
            thread(name = "runtime-extract") {
                if (!NodeRuntime.isRuntimeExtracted(this)) {
                    NodeRuntime.extractRuntime(this)
                }
                // the terminal bootstrap script installs from these tarballs
                NodeRuntime.copyPackages(this)
                runOnUiThread {
                    loading.visibility = View.GONE
                    terminalView.visibility = View.VISIBLE
                    startSession()
                }
            }
        }
    }

    private fun startSession() {
        val script = SetupScripts.zcodeScript(this)
        val env = NodeRuntime.environment(this)
            .map { (k, v) -> "$k=$v" }
            .toTypedArray()
        val workDir = Workspace.workspacePath(this) ?: NodeRuntime.homeDir(this).absolutePath
        val session = TerminalSession(
            "/system/bin/sh",
            workDir,
            arrayOf("/system/bin/sh", script.absolutePath),
            env,
            null,
            sessionClient,
        )
        SessionHolder.current = session
        terminalView.attachSession(session)
        startForegroundService(Intent(this, SessionService::class.java))
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
                Toast.makeText(this@ZcodeTerminalActivity, "会话已结束（退出码 ${finishedSession.getExitStatus()}）", Toast.LENGTH_LONG).show()
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

    override fun onDestroy() {
        // Session intentionally survives activity teardown (SessionHolder) so the
        // TUI can be resumed from the launcher icon.
        super.onDestroy()
    }
}

/** Keeps the terminal session alive across activity restarts. */
object SessionHolder {
    @Volatile var current: TerminalSession? = null
}
