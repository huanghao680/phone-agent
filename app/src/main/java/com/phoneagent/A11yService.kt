package com.phoneagent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility channel for the device tool surface: exposes the semantic tree of
 * the current window to PhoneToolService's /a11y-dump endpoint. Works without
 * Shizuku (the Shizuku path's uiautomator dump conflicts with running app
 * instrumentation on some devices and cannot reach secure windows).
 *
 * Nothing leaves the device: PhoneToolService serves it on loopback only. The
 * service must be enabled by the user in system accessibility settings.
 *
 * Design (ref-handle addressing kept for a later click-through path) follows
 * dsh-mobile-apk's phone tools: dump keeps per-node handles so a follow-up
 * action can target "ref N" without re-resolving paths.
 */
class A11yService : AccessibilityService() {

    companion object {
        @Volatile var instance: A11yService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Depth-first semantic tree snapshot with sequential ref handles. */
    fun dumpTree(limit: Int = 400): Map<String, Any?> {
        val root = rootInActiveWindow ?: return mapOf("error" to "no active window (service enabled?)")
        val nodes = ArrayList<Map<String, Any?>>(limit)
        var nextRef = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || nodes.size >= limit || depth > 24) return
            val b = android.graphics.Rect()
            n.getBoundsInScreen(b)
            nodes.add(
                mapOf(
                    "ref" to nextRef,
                    "pkg" to n.packageName?.toString(),
                    "cls" to n.className?.toString(),
                    "text" to n.text?.toString()?.take(200),
                    "desc" to n.contentDescription?.toString()?.take(200),
                    "id" to n.viewIdResourceName,
                    "clickable" to n.isClickable,
                    "editable" to n.isEditable,
                    "bounds" to "[${b.left},${b.top}][${b.right},${b.bottom}]",
                ),
            )
            nextRef++
            for (i in 0 until n.childCount) {
                try {
                    walk(n.getChild(i), depth + 1)
                } catch (_: Exception) {
                }
            }
        }
        walk(root, 0)
        return mapOf(
            "package" to root.packageName?.toString(),
            "count" to nodes.size,
            "nodes" to nodes,
        )
    }
}
