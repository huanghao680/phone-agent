package com.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A/B slot decisions ride on compareVersions: a wrong sign installs an upgrade
 * that is not one ("anti-downgrade" silently skips) or downgrades a manually
 * updated agent. These pins cover plain, pre-release and mixed comparisons.
 */
class VersionCompareTest {

    private fun cmp(a: String, b: String): Int = AgentSlots.compareVersions(a, b)

    @Test
    fun `plain versions compare numerically`() {
        assertTrue(cmp("0.160.0", "0.157.1") > 0)
        assertTrue(cmp("1.2.3", "1.2.4") < 0)
        assertEquals(0, cmp("2.1.287", "2.1.287"))
    }

    @Test
    fun `two digit patch beats single digit`() {
        assertTrue(cmp("3.14.4", "3.14.3") > 0)
        assertTrue(cmp("0.10.0", "0.9.0") > 0)
    }

    @Test
    fun `prerelease is older than its release`() {
        assertTrue(cmp("0.2.0-rc.2", "0.2.0") < 0)
        assertTrue(cmp("0.1.7-rc.3", "0.1.7") < 0)
    }

    @Test
    fun `prereleases order among themselves`() {
        assertTrue(cmp("0.2.0-rc.3", "0.2.0-rc.2") > 0)
        assertTrue(cmp("0.2.0-alpha.5", "0.2.0-rc.1") < 0)
    }

    @Test
    fun `anti-downgrade guard holds for manual upgrades`() {
        // a newer manually installed version must not be "upgraded" backwards
        assertTrue(cmp("0.161.0", "0.160.0") > 0)
    }
}
