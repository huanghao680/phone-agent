package com.phoneagent

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility channel for the device tool surface: exposes the semantic tree of
 * the current window to PhoneToolService's /a11y-dump endpoint. Works without
 * Shizuku (the Shizuku path's uiautomator dump conflicts with running app
 * instrumentation on some devices and cannot reach secure windows).
 *
 * Nothing leaves the device: PhoneToolService serves it on loopback only.
 *
 * Snapshot design borrowed from callstack/agent-device (4918 stars):
 *  - `mode=interactive` only emits actionable nodes (clickable / editable /
 *    focusable with text), which keeps a dump small enough to put in a prompt;
 *  - refs are `@eN` and stay valid until the next dump, so an agent can act on
 *    the output it just read;
 *  - `diff=true` returns +/-/= lines against the previous snapshot instead of
 *    the whole tree — after an action the agent reads only what changed.
 */
class A11yService : AccessibilityService() {

    companion object {
        @Volatile var instance: A11yService? = null
            private set
    }

    /** Last emitted snapshot, keyed by ref, for diffing. */
    private var lastSnapshot: LinkedHashMap<String, String>? = null

    /**
     * Live node per ref from the last dump, so /a11y-click /a11y-set-text /
     * a11y-text can act on what the agent just read. Entries hold the node as
     * captured; callers refresh() before acting. Cleared on each dump.
     */
    private var lastNodes = LinkedHashMap<String, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        lastNodes.clear()
        super.onDestroy()
    }

    /**
     * @param interactive when true, only actionable nodes are emitted
     * @param diff when true, return +/-/= lines versus the previous dump
     */
    fun dumpTree(limit: Int = 400, interactive: Boolean = false, diff: Boolean = false): Map<String, Any?> {
        val root = rootInActiveWindow
            ?: return mapOf("error" to "no active window (service enabled?)")
        val nodes = ArrayList<Map<String, Any?>>(limit)
        lastNodes.clear()

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || nodes.size >= limit || depth > 24) return
            val b = Rect()
            n.getBoundsInScreen(b)
            val actionable = n.isClickable || n.isEditable || n.isFocusable
            val text = n.text?.toString()
            val desc = n.contentDescription?.toString()
            var label = (text ?: desc ?: "").take(120)
            if (label.isEmpty() && interactive && actionable) label = firstText(n)
            // Compose marks the *container* clickable and keeps the text in a child,
            // so a clickable node with no text of its own is still worth emitting
            val interesting = !interactive ||
                (actionable && (label.isNotEmpty() || n.isEditable || n.viewIdResourceName != null))
            if (interesting) {
                val ref = "@e" + nodes.size
                val kind = when {
                    n.isEditable -> "text-field"
                    n.isClickable -> "button"
                    else -> "text"
                }
                lastNodes[ref] = n
                nodes.add(
                    mapOf(
                        "ref" to ref,
                        "kind" to kind,
                        "text" to if (label.isEmpty()) null else label,
                        "id" to n.viewIdResourceName,
                        "editable" to n.isEditable,
                        "bounds" to "[${b.left},${b.top}][${b.right},${b.bottom}]",
                    ),
                )
            }
            for (i in 0 until n.childCount) {
                try {
                    walk(n.getChild(i), depth + 1)
                } catch (_: Exception) {
                }
            }
        }
        walk(root, 0)

        // stable identity for diffing: text@kind, falling back to bounds
        val current = LinkedHashMap<String, String>()
        for (n in nodes) {
            val key = (n["text"] as? String)?.let { it + "@" + n["kind"] }
                ?: (n["bounds"] as? String) + "@" + n["kind"]
            current[key] = n["ref"] as String
        }

        if (diff) {
            val prev = lastSnapshot
            lastSnapshot = current
            if (prev == null) {
                // first dump has no baseline: emit everything as unchanged so the
                // agent still gets the refs it needs to act
                return mapOf(
                    "diff" to nodes.map { "= " + it["ref"] + " [" + it["kind"] + "] " + (it["text"] ?: "") },
                    "added" to 0,
                    "removed" to 0,
                    "note" to "first dump: no previous state; every node is listed as unchanged, refs now valid",
                )
            }
            val lines = ArrayList<String>()
            for ((k, ref) in current) {
                if (!prev.containsKey(k)) lines += "+ $ref [" + kindOf(k) + "] " + textOf(k)
            }
            for ((k, ref) in prev) {
                if (!current.containsKey(k)) lines += "- $ref [" + kindOf(k) + "] " + textOf(k)
            }
            return mapOf(
                "diff" to lines,
                "added" to current.keys.count { !prev.containsKey(it) },
                "removed" to prev.keys.count { !current.containsKey(it) },
                "note" to "refs are valid only for this output; dump again for new refs",
            )
        }

        lastSnapshot = current
        return mapOf(
            "package" to root.packageName?.toString(),
            "mode" to if (interactive) "interactive" else "full",
            "count" to nodes.size,
            "nodes" to nodes,
        )
    }

    /** Drops the diff baseline; call after switching apps or after a long gap. */
    fun resetBaseline() {
        lastSnapshot = null
    }

    /**
     * Clicks the node a ref from the last dump points at. Works cross-app —
     * the semantic tree is whatever window is focused, no Shizuku needed.
     * Compose keeps the clickable flag on the container, so when the ref'd
     * node itself is not clickable the walk climbs to its ancestors (3 levels)
     * before giving up.
     */
    fun clickByRef(ref: String, longClick: Boolean = false): Map<String, Any?> {
        val n = resolveNode(ref)
            ?: return mapOf(
                "error" to "ref 不存在或已失效（$ref），请重新 /a11y-dump 获取新 refs",
                "knownRefs" to lastNodes.keys.toList().take(40),
            )
        val action = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var target: AccessibilityNodeInfo? = n
        var climbed = 0
        while (target != null && climbed <= 3) {
            target.refresh()
            if (if (longClick) target.isLongClickable else target.isClickable) break
            target = try {
                target.parent
            } catch (_: Exception) {
                null
            }
            climbed++
        }
        val node = target
            ?: return mapOf(
                "error" to "$ref 不可点击，也无祖先节点可点击",
                "bounds" to boundsOf(n),
                "hint" to "改用 /tap 加 bounds 中心坐标",
            )
        val ok = node.performAction(action)
        return mapOf(
            "clicked" to ok,
            "ref" to ref,
            "long" to longClick,
            "climbed" to climbed,
            "bounds" to boundsOf(node),
            "text" to node.text?.toString()?.take(120),
        )
    }

    /**
     * Writes text into an editable field by ref — the CJK-safe path into input
     * boxes (Android's `input text` cannot inject non-ASCII, and injected
     * Ctrl+V paste is unreliable across IMEs). The caller should verify with
     * /a11y-text instead of trusting the return value.
     */
    fun setTextByRef(ref: String, text: String): Map<String, Any?> {
        val n = resolveNode(ref)
            ?: return mapOf(
                "error" to "ref 不存在或已失效（$ref），请重新 /a11y-dump 获取新 refs",
                "knownRefs" to lastNodes.keys.toList().take(40),
            )
        n.refresh()
        if (!n.isEditable) {
            return mapOf(
                "error" to "$ref 不是可编辑字段（kind 非 text-field）",
                "bounds" to boundsOf(n),
                "text" to n.text?.toString()?.take(120),
            )
        }
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return mapOf(
            "set" to ok,
            "ref" to ref,
            "length" to text.length,
            "hint" to "用 /a11y-text?ref=$ref 读回字段实值确认，别信返回值",
        )
    }

    /** Reads back a node's current text — the dump-field-reality check. */
    fun textByRef(ref: String): Map<String, Any?> {
        val n = resolveNode(ref)
            ?: return mapOf(
                "error" to "ref 不存在或已失效（$ref），请重新 /a11y-dump 获取新 refs",
                "knownRefs" to lastNodes.keys.toList().take(40),
            )
        n.refresh()
        val text = n.text?.toString()
        val desc = n.contentDescription?.toString()
        return mapOf(
            "ref" to ref,
            "text" to text,
            "desc" to desc,
            "editable" to n.isEditable,
            "bounds" to boundsOf(n),
        )
    }

    /** Normalizes "@e12" / "e12" / "@eN" spellings and returns the captured node. */
    private fun resolveNode(ref: String): AccessibilityNodeInfo? {
        val key = ref.trim().let { if (it.startsWith("@")) it else "@$it" }
        return lastNodes[key]
    }

    private fun boundsOf(n: AccessibilityNodeInfo): String {
        val b = Rect()
        n.getBoundsInScreen(b)
        return "[${b.left},${b.top}][${b.right},${b.bottom}]"
    }

    /** Nearest visible text under a node — Compose hides labels in child nodes. */
    private fun firstText(n: AccessibilityNodeInfo, depth: Int = 0): String {
        if (depth > 3) return ""
        for (i in 0 until n.childCount) {
            val c = try {
                n.getChild(i)
            } catch (_: Exception) {
                continue
            } ?: continue
            val t = c.text?.toString()?.trim().orEmpty().ifEmpty { c.contentDescription?.toString()?.trim().orEmpty() }
            if (t.isNotEmpty()) return t.take(120)
            val deeper = firstText(c, depth + 1)
            if (deeper.isNotEmpty()) return deeper
        }
        return ""
    }

    private fun kindOf(key: String): String = key.substringAfterLast('@')

    private fun textOf(key: String): String = key.substringBeforeLast('@')
}
