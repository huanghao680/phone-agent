package com.phoneagent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File

/**
 * Accepts files handed over from other apps (share sheet + "open with") and turns
 * them into a fresh session: the file is copied into a private temp workspace so
 * the agent has a real path it can read, then the terminal opens there with the
 * paths remembered for the first prompt.
 *
 * Credit: the dispatch shape follows dsh-mobile-apk's file-open surface; the
 * temp workspace is swept by age (7 days) on startup.
 */
class FileIncomingActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    private fun handle(src: Intent?) {
        val uris = ArrayList<Uri>()
        when (src?.action) {
            Intent.ACTION_SEND -> src.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE ->
                src.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            Intent.ACTION_VIEW -> src.data?.let { uris += it }
        }
        if (uris.isEmpty()) {
            openTerminal(null)
            return
        }
        val ws = ensureTempWorkspace(this)
        val copied = ArrayList<String>()
        for (u in uris) {
            val name = displayName(this, u) ?: ("incoming-" + System.currentTimeMillis())
            val dest = File(ws, name)
            try {
                contentResolver.openInputStream(u)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                copied += dest.absolutePath
            } catch (_: Exception) {
                // one unreadable attachment shouldn't sink the handoff
            }
        }
        openTerminal(if (copied.isEmpty()) null else copied)
    }

    private fun openTerminal(paths: List<String>?) {
        SessionHolder.incomingFiles = paths
        SessionHolder.pendingAgent = "zcode"
        startActivity(
            Intent(this, ZcodeTerminalActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (paths != null) putExtra("incoming", paths.joinToString("\n"))
            },
        )
    }

    companion object {
        private const val TMP = "files/incoming"

        /** Shared temp workspace for handed-over files; created on demand. */
        fun ensureTempWorkspace(ctx: Context): File {
            val dir = File(ctx.filesDir, "incoming")
            dir.mkdirs()
            File(dir, ".gitignore").writeText("*\n")
            return dir
        }

        /**
         * Drops inbound files older than 7 days. Called on app start; cheap enough
         * that it never needs its own background job.
         */
        fun sweepExpired(ctx: Context, maxAgeDays: Int = 7) {
            val dir = File(ctx.filesDir, "incoming")
            if (!dir.isDirectory) return
            val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 3600 * 1000
            for (f in dir.listFiles() ?: return) {
                if (f.name == ".gitignore") continue
                if (f.lastModified() < cutoff) f.deleteRecursively()
            }
        }

        fun displayName(ctx: Context, uri: Uri): String? = try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment
    }
}
