package com.phoneagent

import android.util.Log
import org.json.JSONObject

/**
 * Runs third-party apps on a private virtual display so an agent can drive them
 * without occupying the user's foreground.
 *
 * Creation needs a shell-uid process (the app cannot create a display itself), so
 * this rides our existing Shizuku shell channel with `cmd display create-display`.
 * Launching onto it uses `am start --display <id>`, which dsh-mobile-apk measured
 * as the only workable cross-display launch path (monkey has no --display, shell
 * `am start` is unreliable, and in-process setLaunchDisplayId is rejected by
 * SafeActivityOptions).
 *
 * Coordinates are always absolute pixels dispatched with `input -d <displayId>`:
 * normalized coordinates are refused deliberately, since the denominator is
 * ambiguous and a mistake silently taps the user's real screen.
 */
object VirtualDisplay {

    private const val TAG = "phone-vdisplay"

    data class Screen(val id: Int, val alias: String)

    /** virtual-1 is always "the next free slot". */
    const val ALIAS = "virtual-1"

    fun available(): Boolean = ShizukuHelper.granted()

    /** Creates a display and returns `(displayId, message)`. */
    /**
     * `cmd display create-display` only exists on some builds (Android 17 here
     * does not ship it), so probe the command surface first instead of reporting
     * a bare "failed" that looks like a bug in us.
     */
    fun supported(): Boolean {
        if (!available()) return false
        val help = run("cmd display help 2>&1")
        return help.contains("create-display")
    }

    fun create(): Pair<Int?, String> {
        if (!available()) return null to "Shizuku 未授权"
        if (!supported()) {
            return null to ("本机不支持创建虚拟屏（cmd display 无 create-display 子命令）。" +
                "仅支持多屏的 ROM/汽车版 Android 提供该能力。")
        }
        val before = displayIds()
        run("cmd display create-display --type VIRTUAL 'Phone Agent' 2>&1")
        val after = displayIds()
        val id = after.firstOrNull { it !in before }
        return if (id == null) null to ("创建后未见新 display：" + after) else id to "已创建 display $id"
    }

    fun destroy(displayId: Int): String {
        if (!available()) return "Shizuku 未授权"
        return run("cmd display destroy-display $displayId 2>&1").take(200).ifBlank { "已销毁 display $displayId" }
    }

    /** Launches a component on a display; component is `pkg/activity`. */
    fun launch(component: String, displayId: Int): String {
        if (!available()) return "Shizuku 未授权"
        val resolved = run("cmd package resolve-activity --brief '$component' 2>&1 | tail -1").trim()
        val target = if (resolved.isBlank() || resolved.startsWith("No activity")) component else resolved
        return run("am start --display $displayId -n '$target' 2>&1").take(300).ifBlank { "已拉起 $target" }
    }

    /** Absolute-pixel tap on a specific display. */
    fun tap(x: Int, y: Int, displayId: Int): String {
        if (!available()) return "Shizuku 未授权"
        return run("input -d $displayId tap $x $y 2>&1").take(120).ifBlank { "已点击 ($x,$y) on display $displayId" }
    }

    fun screenshot(displayId: Int): ByteArray? {
        if (!available()) return null
        val p = ShizukuHelper.sh("screencap -d $displayId -p 2>/dev/null")
        val bytes = try {
            p.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            ByteArray(0)
        }
        try {
            p.waitFor()
        } catch (_: Exception) {
        }
        return if (bytes.isEmpty()) null else bytes
    }

    /** Lists displays with their ids, best effort. */
    fun list(): String {
        if (!available()) return "Shizuku 未授权"
        val ids = displayIds()
        return if (ids.isEmpty()) "未识别到 display（dumpsys 解析为空）" else "display id: " + ids.joinToString(", ")
    }

    /** Display ids from `dumpsys display`, which unlike `cmd display` is stable. */
    fun displayIds(): List<Int> {
        val out = run("dumpsys display 2>&1")
        return Regex("""Display [iI]d=(\d+)""").findAll(out).map { it.groupValues[1].toInt() }.distinct().toList()
    }

    private fun run(cmd: String): String {
        return try {
            val p = ShizukuHelper.sh(cmd)
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val err = try {
                p.errorStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                ""
            }
            p.waitFor()
            (out + err).trim()
        } catch (e: Exception) {
            Log.w(TAG, "run failed", e)
            ""
        }
    }

}
