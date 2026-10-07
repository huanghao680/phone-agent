package com.phoneagent

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * APK self-update check against this repo's GitHub releases. Deliberately
 * manual (a settings button, no background polling): the app itself never
 * downloads or installs anything — a newer release just opens its download page,
 * keeping REQUEST_INSTALL_PACKAGES out of the picture entirely.
 */
object ApkUpdate {

    private const val REPO = "huanghao680/phone-agent"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    data class Info(val latestTag: String, val newer: Boolean, val notes: String)

    /** Compares GitHub's latest release tag against the running versionName. */
    fun check(ctx: Context): Info {
        val conn = AgentUpdate.open(ctx, LATEST)
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val o = JSONObject(body)
        val tag = o.optString("tag_name").removePrefix("v")
        val notes = o.optString("body").take(400)
        val current = ctx.packageManager
            .getPackageInfo(ctx.packageName, 0).versionName ?: ""
        return Info(tag, isNewer(tag, current), notes)
    }

    fun openReleasesPage(ctx: Context) {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Numeric-aware compare so v0.7.9 > v0.7.10 stays false. */
    internal fun isNewer(tag: String, current: String): Boolean {
        val a = tag.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
