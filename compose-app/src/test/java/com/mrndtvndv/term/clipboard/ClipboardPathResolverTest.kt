package com.mrndtvndv.term.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPathResolverTest {

    @Test
    fun resolveRemoteTargetDir_nullWhenNullOrEmpty() {
        assertNull(ClipboardPathResolver.resolveRemoteTargetDir(null))
        assertNull(ClipboardPathResolver.resolveRemoteTargetDir(""))
        assertNull(ClipboardPathResolver.resolveRemoteTargetDir("   "))
    }

    @Test
    fun resolveRemoteTargetDir_returnsTrimmedNonEmptyPath() {
        val path = "/Users/steven/Downloads"
        assertEquals(path, ClipboardPathResolver.resolveRemoteTargetDir(path))
        assertEquals("/tmp/clipboard", ClipboardPathResolver.resolveRemoteTargetDir("/tmp/clipboard"))
        assertEquals("/var/data", ClipboardPathResolver.resolveRemoteTargetDir("  /var/data  "))
        assertEquals(
            "~/.cache/termux-ghostty",
            ClipboardPathResolver.resolveRemoteTargetDir(" ~/.cache/termux-ghostty "),
        )
    }

    @Test
    fun resolveRemoteTargetDir_rejectsNulAndShellQuoteProtectsMetacharacters() {
        assertNull(ClipboardPathResolver.resolveRemoteTargetDir("/tmp/unsafe\u0000path"))

        val target = "/tmp/a path/'; touch /tmp/pwned; #"
        val command = ClipboardPathResolver.buildRemoteDirectoryCommand(target)

        assertTrue(command.contains("target=${ClipboardPathResolver.shellQuote(target)};"))
        assertFalse(command.contains("target=\"$target\";"))
    }

    @Test
    fun defaultImageCacheDir_isTildePrefixed() {
        assertEquals("~/.cache/termux-ghostty", ClipboardPathResolver.DEFAULT_IMAGE_CACHE_DIR)
    }

    @Test
    fun expandLocalHome_expandsTildeAndSubpaths() {
        val fakeHome = "/home/testuser"
        assertEquals("/home/testuser", ClipboardPathResolver.expandLocalHome("~", fakeHome))
        assertEquals("/home/testuser/foo", ClipboardPathResolver.expandLocalHome("~/foo", fakeHome))
        assertEquals(
            "/home/testuser/.cache/termux-ghostty",
            ClipboardPathResolver.expandLocalHome("~/.cache/termux-ghostty", fakeHome),
        )
        assertEquals("/absolute/path", ClipboardPathResolver.expandLocalHome("/absolute/path", fakeHome))
    }

    @Test
    fun resolveLocalTargetPath_usesTheApplicationHomeOverride() {
        assertEquals(
            "/data/user/0/app/files/.cache/termux-ghostty",
            ClipboardPathResolver.resolveLocalTargetPath(
                "~/.cache/termux-ghostty",
                "/data/user/0/app/files",
            ),
        )
    }

    @Test
    fun resolveLocalTargetPath_nullWhenNullOrEmpty() {
        assertNull(ClipboardPathResolver.resolveLocalTargetPath(null))
        assertNull(ClipboardPathResolver.resolveLocalTargetPath(""))
        assertNull(ClipboardPathResolver.resolveLocalTargetPath("   "))
    }

    @Test
    fun resolveLocalDirectory_returnsNullWhenNoCustomDirectory() {
        assertNull(ClipboardPathResolver.resolveLocalDirectory(null))
        assertNull(ClipboardPathResolver.resolveLocalDirectory(""))
        assertNull(ClipboardPathResolver.resolveLocalDirectory("   "))
        assertNotNull(ClipboardPathResolver.resolveLocalDirectory("/tmp/test_dir"))
    }
}
