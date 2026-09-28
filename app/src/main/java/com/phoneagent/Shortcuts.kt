package com.phoneagent

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Recreates the launcher shortcuts for all entry points. Users can delete
 * pinned shortcuts from their launcher; this puts them back without
 * reinstalling (launcher icon itself always survives a reinstall, but the
 * extra pinned shortcuts do not).
 */
object Shortcuts {

    private data class Def(val id: String, val label: String, val cls: Class<*>, val icon: Int)

    private fun defs() = listOf(
        Def("shortcut_zcode", "Zcode", ZcodeWebActivity::class.java, R.mipmap.ic_zcode),
        Def("shortcut_dsh", "DeepSeek Harness", DshActivity::class.java, R.mipmap.ic_dsh),
        Def("shortcut_zweb", "Zcode Web", ZcodeWebActivity::class.java, R.mipmap.ic_zcode_web),
        Def("shortcut_terminal", "终端", TerminalPickerActivity::class.java, R.mipmap.ic_launcher),
    )

    /** Pins all entry shortcuts again (idempotent: existing ones are updated). */
    fun recreateAll(ctx: Context): List<String> {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
            return listOf("当前桌面不支持固定快捷方式（Pinned shortcuts）")
        }
        val mgr = ctx.getSystemService(Context.SHORTCUT_SERVICE) as ShortcutManager
        val log = mutableListOf<String>()
        for (d in defs()) {
            val intent = Intent(Intent.ACTION_VIEW).setClassName(ctx, d.cls.name)
            val info = ShortcutInfoCompat.Builder(ctx, d.id)
                .setShortLabel(d.label)
                .setIcon(IconCompat.createWithResource(ctx, d.icon))
                .setIntent(intent)
                .build()
            val ok = try {
                // update in place if already pinned, otherwise request pinning
                val existing = mgr.pinnedShortcuts.firstOrNull { it.id == d.id }
                if (existing != null) {
                    ShortcutManagerCompat.updateShortcuts(ctx, listOf(info))
                    true
                } else {
                    ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
                    true
                }
            } catch (e: Exception) {
                log.add("[fail] ${d.label}: ${e.message}")
                false
            }
            if (ok) log.add("[ok] ${d.label}")
        }
        log.add("桌面弹出确认后即可恢复；若桌面未弹出，请检查启动器是否允许固定快捷方式。")
        return log
    }
}
