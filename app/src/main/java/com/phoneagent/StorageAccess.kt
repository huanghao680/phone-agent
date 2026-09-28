package com.phoneagent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Grants the app (and therefore the embedded Node/CLI processes) full access
 * to shared storage, so the agents' own file dialogs show every folder on the
 * device instead of being limited to the app-private directory.
 *
 * Two mechanisms depending on Android version:
 * - Android 11+ (R): MANAGE_EXTERNAL_STORAGE via the special "all files
 *   access" settings page. Covers /sdcard for child processes directly.
 * - Android 9/10 (P/Q): legacy WRITE_EXTERNAL_STORAGE grant + requestLegacy-
 *   ExternalStorage flag (targetSdk 28 already opts in implicitly).
 *
 * The default working directory becomes /sdcard once access is granted; a
 * finer-grained SAF workspace (Settings > 工作区) still wins if configured.
 */
object StorageAccess {

    fun isAllFilesGranted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    fun needsSpecialScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 30 && !isAllFilesGranted(ctx)

    fun buildAllFilesIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:com.phoneagent"),
        )

    /**
     * Default cwd for agent processes: the app-private home.
     *
     * /storage/emulated/0 is a FUSE mount mounted noexec — spawning bash or
     * ripgrep from it fails with EACCES, and its path normalization makes
     * tools compute temp names one directory too high, so writes fail too.
     * The private home is on a normal executable filesystem, which keeps the
     * agent's bash/glob/grep/write tools working. Users can still point the
     * workspace at a specific folder via the SAF picker (browsing files there
     * works; only executing binaries from it is restricted by the platform).
     */
    fun defaultWorkspace(ctx: Context): String = when {
        Workspace.workspacePath(ctx) != null -> Workspace.workspacePath(ctx)!!
        else -> NodeRuntime.homeDir(ctx).absolutePath
    }

    /** True when the path is on the FUSE shared-storage mount (noexec). */
    fun onFuseMount(path: String): Boolean = path.startsWith("/storage/emulated/") ||
        path.startsWith("/sdcard")
}
