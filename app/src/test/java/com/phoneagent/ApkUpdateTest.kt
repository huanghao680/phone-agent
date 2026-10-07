package com.phoneagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Numeric-aware release-tag comparison feeding the manual APK update check. */
class ApkUpdateTest {

    @Test
    fun `patch bump is newer`() {
        assertTrue(ApkUpdate.isNewer("0.7.91", "0.7.90"))
    }

    @Test
    fun `minor bump beats larger patch`() {
        assertTrue(ApkUpdate.isNewer("0.8.0", "0.7.99"))
        assertTrue(ApkUpdate.isNewer("1.0.0", "0.9.99"))
    }

    @Test
    fun `same version is not newer`() {
        assertFalse(ApkUpdate.isNewer("0.7.91", "0.7.91"))
    }

    @Test
    fun `older tag is not newer`() {
        assertFalse(ApkUpdate.isNewer("0.7.9", "0.7.10"))
        assertFalse(ApkUpdate.isNewer("0.6.0", "0.7.1"))
    }

    @Test
    fun `short tags compare field-wise`() {
        assertTrue(ApkUpdate.isNewer("0.8", "0.7.5"))
        assertFalse(ApkUpdate.isNewer("0.7", "0.7.1"))
    }
}
