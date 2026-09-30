package com.phoneagent

/**
 * Pinned component versions. MUST stay in sync with scripts/versions.env,
 * which drives the CI scripts that stage the matching APK assets.
 */
object Versions {
    const val RUNTIME = "node-26.4.0-3"
    const val ZCODE = "3.14.4-29"
    const val DSH = "0.2.0-rc.2"
    const val ZCODE_UPSTREAM_REF = "v3.14.3"
    const val CODEX_VERSION = "0.157.1"
    const val CLAUDE_VERSION = "2.1.283"
    /** opencode: Bun single-file executable, launched via the Alpine musl loader. */
    const val OPENCODE_VERSION = "1.18.33"
    /** Static musl ripgrep that backs dsh's glob/grep on Android. */
    const val RIPGREP_VERSION = "1.18.0"

    /**
     * pnpm 10 for dsh's plugin manager. pnpm 12 ships a native executable whose
     * store operation lock (flock-based) is unsupported on the target
     * filesystems; 10.x is pure JS and verified working on device.
     */
    const val PNPM_VERSION = "10.34.6"

    const val ZCODE_TGZ = "zcode-app-cli-$ZCODE.tgz"
    const val DSH_TGZ = "deepseek-ai-dsh-$DSH.tgz"

    /** Marker for the extracted official web UI + server bundle. */
    const val ZCODE_WEB = "zcode-web-${ZCODE}-${ZCODE_UPSTREAM_REF}"

    /** Marker content stored after a successful CLI install. */
    fun packagesMarker(): String = "zcode-app-cli@$ZCODE @deepseek-ai/dsh@$DSH"
}
