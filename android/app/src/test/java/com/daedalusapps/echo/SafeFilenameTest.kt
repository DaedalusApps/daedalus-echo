package com.daedalusapps.echo

import com.daedalusapps.echo.util.SafeFilename
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SafeFilename.isSafe] is a pure character allowlist ([A-Za-z0-9._-]+), which lets `.`, `..`,
 * and any run of dots (`....`) pass — they are all path-traversal shaped, and none is ever a
 * legitimate recording filename.
 */
class SafeFilenameTest {

    @Test
    fun `rejects dot-only names`() {
        assertFalse(SafeFilename.isSafe("."))
        assertFalse(SafeFilename.isSafe(".."))
        assertFalse(SafeFilename.isSafe("...."))
    }

    @Test
    fun `rejects blank and invalid characters`() {
        assertFalse(SafeFilename.isSafe(""))
        assertFalse(SafeFilename.isSafe("   "))
        assertFalse(SafeFilename.isSafe("../foo.mp3"))
        assertFalse(SafeFilename.isSafe("foo/bar.mp3"))
        assertFalse(SafeFilename.isSafe("foo;rm -rf"))
    }

    @Test
    fun `accepts legitimate recording and import filenames`() {
        assertTrue(SafeFilename.isSafe("20260812102746"))
        assertTrue(SafeFilename.isSafe("recording_01"))
        assertTrue(SafeFilename.isSafe("foo.mp3"))
        assertTrue(SafeFilename.isSafe("bar.m4a"))
    }
}
