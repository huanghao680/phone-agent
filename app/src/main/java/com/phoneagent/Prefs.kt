package com.phoneagent

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "phone_agent"

    // npmmirror went unreachable (verified 2026-09-28); Tencent's npm mirror
    // is the same sync source and responded 200 for package queries
    const val DEFAULT_REGISTRY = "https://mirrors.cloud.tencent.com/npm/"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun npmRegistry(ctx: Context): String =
        sp(ctx).getString("npm_registry", DEFAULT_REGISTRY)!!.ifBlank { DEFAULT_REGISTRY }

    fun setNpmRegistry(ctx: Context, v: String) {
        sp(ctx).edit().putString("npm_registry", v.trim()).apply()
    }

    /** HTTP proxy for all Node network traffic, e.g. http://192.168.1.197:7890. Empty = direct. */
    fun httpProxy(ctx: Context): String = sp(ctx).getString("http_proxy", "")?.trim() ?: ""

    fun setHttpProxy(ctx: Context, v: String) {
        sp(ctx).edit().putString("http_proxy", v.trim()).apply()
    }

    /**
     * One-time migration: the DeepSeek key used to live in this plain file;
     * move it to the encrypted store on first read after the upgrade.
     */
    /**
     * Global web-UI zoom, in percent (50..150). Applied as the WebView's
     * initial scale AND as the viewport width factor, so the chosen ratio is
     * what the user actually sees, not just a pinch starting point.
     */
    fun webUiScale(ctx: Context): Int = sp(ctx).getInt("web_ui_scale", 100).coerceIn(50, 150)

    fun setWebUiScale(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("web_ui_scale", v.coerceIn(50, 150)).apply()
    }

    /** Restore the previously running web engines after a reboot. */
    fun restoreOnBoot(ctx: Context): Boolean = sp(ctx).getBoolean("restore_on_boot", false)

    fun setRestoreOnBoot(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("restore_on_boot", v).apply()
    }

    fun migrateLegacyKey(ctx: Context) {
        val legacy = sp(ctx).getString("deepseek_key", null)
        if (!legacy.isNullOrEmpty()) {
            SecretStore.setDeepseekKey(ctx, legacy)
            sp(ctx).edit().remove("deepseek_key").apply()
        }
    }
}
