package com.phoneagent

import android.content.Context
import java.io.File

/**
 * Pre-seeds the zcode CLI config so its atomic writer never needs link().
 *
 * zcode writes setting.json via write-to-temp + link(), but Android 11+
 * SELinux blocks link() for app domains on many devices (verified on Android
 * 11 and 13). Renaming is allowed everywhere, so we materialize a minimal
 * valid setting.json ourselves; the CLI then keeps editing it in place.
 */
object ZcodeConfig {

    fun seed(ctx: Context) {
        val cliDir = File(NodeRuntime.homeDir(ctx), ".zcode/cli")
        val setting = File(cliDir, "setting.json")
        if (setting.exists()) return
        cliDir.mkdirs()
        val tmp = File(cliDir, ".seed-${System.currentTimeMillis()}.tmp")
        tmp.writeText("{}\n")
        if (!tmp.renameTo(setting)) {
            // fall back to a direct write; some filesystems allow this too
            tmp.delete()
            setting.writeText("{}\n")
        }
    }
}
