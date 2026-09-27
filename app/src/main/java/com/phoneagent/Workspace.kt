package com.phoneagent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings

/**
 * Workspace selection via the Storage Access Framework. The user picks any
 * folder once; we take a persistable URI permission and remember the tree.
 *
 * SAF trees are exposed to shell tools through the app-private fuse mount at
 * /dev/fuse or, more usefully, via Android's documented passthrough at
 * /storage/emulated/<uid>/… — but tool-side access needs a real path, so we
 * map the picked tree to a bindable path through DocumentFile resolution.
 */
object Workspace {

    const val REQUEST_PICK = 7001

    fun pick(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(
                "android.provider.extra.INITIAL_URI",
                "content://com.android.externalstorage.documents/document/primary%3A",
            )
        }
        activity.startActivityForResult(intent, REQUEST_PICK)
    }

    fun persist(ctx: Context, treeUri: Uri) {
        ctx.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        sp(ctx).edit()
            .putString("workspace_tree", treeUri.toString())
            .putLong("workspace_granted_at", System.currentTimeMillis())
            .apply()
    }

    fun treeUri(ctx: Context): String? =
        sp(ctx).getString("workspace_tree", null)

    fun clear(ctx: Context) {
        sp(ctx).edit().remove("workspace_tree").remove("workspace_path").apply()
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("phone_agent", Context.MODE_PRIVATE)

    /**
     * Physical path the CLI can use as cwd. For the common case of a primary
     * storage tree this is derivable from the tree's document id
     * ("primary:Dir/Sub"), otherwise null (the picker result is not a real
     * filesystem folder we can chdir into).
     */
    fun workspacePath(ctx: Context): String? {
        val tree = treeUri(ctx) ?: return null
        val uri = Uri.parse(tree)
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!docId.startsWith("primary:")) return null
        val rel = docId.removePrefix("primary:")
        return if (rel.isEmpty()) {
            "/sdcard"
        } else {
            "/sdcard/$rel"
        }
    }

    /** True when the persisted grant is still valid (survives reboots). */
    fun isGrantValid(ctx: Context): Boolean {
        val tree = treeUri(ctx) ?: return false
        return try {
            ctx.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == tree && it.isReadPermission
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Environment overlay for CLI processes when a workspace is configured. */
    fun envOverlay(ctx: Context): Map<String, String> {
        val path = workspacePath(ctx) ?: return emptyMap()
        return mapOf(
            "PHONE_AGENT_WORKSPACE" to path,
        )
    }
}
