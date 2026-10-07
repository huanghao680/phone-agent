package com.phoneagent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The version double-lock: scripts/versions.env (drives CI staging) and
 * Versions.kt (drives app-side install checks) must agree. A drift here made
 * dsh stick on an old version once — the app kept "installing" the env version
 * while the code compared against the stale constant.
 */
class VersionsLockTest {

    private fun env(): Map<String, String> {
        val f = File("../scripts/versions.env")
        assertTrue("scripts/versions.env not found at ${f.absolutePath}", f.isFile)
        val map = HashMap<String, String>()
        for (line in f.readLines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val i = t.indexOf('=')
            if (i > 0) map[t.substring(0, i).trim()] = t.substring(i + 1).trim()
        }
        return map
    }

    @Test
    fun `zcode version matches env`() {
        val e = env()
        assertTrue(Versions.ZCODE == e["ZCODE"] || Versions.ZCODE == e["ZCODE_VERSION"])
    }

    @Test
    fun `dsh version matches env`() {
        val e = env()
        assertTrue(Versions.DSH == e["DSH"] || Versions.DSH == e["DSH_VERSION"])
    }

    @Test
    fun `codex version matches env`() {
        val e = env()
        val v = e["CODEX"] ?: e["CODEX_VERSION"]
        assertTrue("CODEX missing in versions.env", v != null)
        assertTrue(Versions.CODEX_VERSION == v)
    }
}
