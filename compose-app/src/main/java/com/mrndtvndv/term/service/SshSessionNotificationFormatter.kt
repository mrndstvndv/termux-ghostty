package com.mrndtvndv.term.service

internal object SshSessionNotificationFormatter {
    fun formatTitleAndText(
        sshCount: Int,
        localCount: Int,
        wakeLockHeld: Boolean,
    ): Pair<String, String> {
        val totalSessions = sshCount + localCount
        if (totalSessions == 0) {
            val title = if (wakeLockHeld) "Wake Lock Active" else "Terminal Service"
            val text = if (wakeLockHeld) "Wake lock held (no active sessions)" else "No active sessions"
            return Pair(title, text)
        }

        val title = when {
            sshCount > 0 && localCount > 0 -> "Active Terminal Sessions"
            sshCount > 0 -> if (sshCount > 1) "SSH Sessions Active" else "SSH Session Active"
            else -> if (localCount > 1) "Local Sessions Active" else "Local Session Active"
        }

        val baseText = when {
            sshCount > 0 && localCount > 0 ->
                "Maintaining $sshCount SSH and $localCount local terminal sessions"
            sshCount > 0 -> {
                if (sshCount > 1) "Maintaining $sshCount active SSH terminal sessions"
                else "Maintaining active SSH terminal session"
            }
            else -> {
                if (localCount > 1) "Maintaining $localCount active local terminal sessions"
                else "Maintaining active local terminal session"
            }
        }

        val text = if (wakeLockHeld) "$baseText (wake lock held)" else baseText
        return Pair(title, text)
    }
}
