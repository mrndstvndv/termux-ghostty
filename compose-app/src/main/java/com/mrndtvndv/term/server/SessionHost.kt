package com.mrndtvndv.term.server

import androidx.lifecycle.Lifecycle
import com.termux.terminal.TerminalSession

/**
 * The live UI half of the session manager, implemented by MainActivity.
 *
 * The manager holds only a weak reference so an activity that is destroyed
 * (e.g. dismissed from recents) can be garbage-collected while the sessions
 * it was rendering continue running in the background.
 */
interface SessionHost {
    fun onFrameAvailable(session: TerminalSession)

    fun copyToClipboard(text: String)

    fun pasteFromClipboard(): String?

    fun isAtLeast(state: Lifecycle.State): Boolean

    fun showInAppNotification(title: String?, body: String?, serverId: String? = null)
}
