package com.phoneagent

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "phone_agent"

    const val DEFAULT_REGISTRY = "https://registry.npmmirror.com"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun deepseekKey(ctx: Context): String = sp(ctx).getString("deepseek_key", "") ?: ""

    fun setDeepseekKey(ctx: Context, v: String) {
        sp(ctx).edit().putString("deepseek_key", v).apply()
    }

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
}
