package com.mrndtvndv.term.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SshSessionNotificationFormatterTest {

    @Test
    fun `zero sessions without wake lock shows truthful default notification`() {
        val (title, text) = SshSessionNotificationFormatter.formatTitleAndText(
            sshCount = 0,
            localCount = 0,
            wakeLockHeld = false,
        )
        assertEquals("Terminal Service", title)
        assertEquals("No active sessions", text)
    }

    @Test
    fun `zero sessions with wake lock shows truthful wake lock active notification`() {
        val (title, text) = SshSessionNotificationFormatter.formatTitleAndText(
            sshCount = 0,
            localCount = 0,
            wakeLockHeld = true,
        )
        assertEquals("Wake Lock Active", title)
        assertEquals("Wake lock held (no active sessions)", text)
    }

    @Test
    fun `single ssh session without wake lock`() {
        val (title, text) = SshSessionNotificationFormatter.formatTitleAndText(
            sshCount = 1,
            localCount = 0,
            wakeLockHeld = false,
        )
        assertEquals("SSH Session Active", title)
        assertEquals("Maintaining active SSH terminal session", text)
    }

    @Test
    fun `multiple ssh sessions with wake lock`() {
        val (title, text) = SshSessionNotificationFormatter.formatTitleAndText(
            sshCount = 3,
            localCount = 0,
            wakeLockHeld = true,
        )
        assertEquals("SSH Sessions Active", title)
        assertEquals("Maintaining 3 active SSH terminal sessions (wake lock held)", text)
    }

    @Test
    fun `single local session without wake lock`() {
        val (title, text) = SshSessionNotificationFormatter.formatTitleAndText(
            sshCount = 0,
            localCount = 1,
            wakeLockHeld = false,
        )
        assertEquals("Local Session Active", title)
        assertEquals("Maintaining active local terminal session", text)
    }

    @Test
    fun `multiple local sessions with wake lock`() {
        val (title, text) = SshSessionNotificationFormatter.formatTitleAndText(
            sshCount = 0,
            localCount = 2,
            wakeLockHeld = true,
        )
        assertEquals("Local Sessions Active", title)
        assertEquals("Maintaining 2 active local terminal sessions (wake lock held)", text)
    }

    @Test
    fun `mixed ssh and local sessions with wake lock`() {
        val (title, text) = SshSessionNotificationFormatter.formatTitleAndText(
            sshCount = 2,
            localCount = 1,
            wakeLockHeld = true,
        )
        assertEquals("Active Terminal Sessions", title)
        assertEquals("Maintaining 2 SSH and 1 local terminal sessions (wake lock held)", text)
    }

    @Test
    fun `mixed ssh and local sessions without wake lock`() {
        val (title, text) = SshSessionNotificationFormatter.formatTitleAndText(
            sshCount = 1,
            localCount = 1,
            wakeLockHeld = false,
        )
        assertEquals("Active Terminal Sessions", title)
        assertEquals("Maintaining 1 SSH and 1 local terminal sessions", text)
    }
}
