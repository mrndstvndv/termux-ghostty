package com.mrndtvndv.term.server

import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Immutable, UI-observable snapshot of a session's Ghostty progress state. */
data class TerminalProgress(
    val state: Int,
    val value: Int?,
    val generation: Long,
) {
    companion object {
        fun from(session: TerminalSession): TerminalProgress? {
            if (session.ghosttyProgressState == TerminalSession.GHOSTTY_PROGRESS_STATE_NONE) {
                return null
            }
            return TerminalProgress(
                state = session.ghosttyProgressState,
                value = session.ghosttyProgressValue.takeIf { it >= 0 },
                generation = session.ghosttyProgressGeneration,
            )
        }
    }
}

/**
 * Process-scoped bridge from terminal callbacks to the Compose UI.
 *
 * Each session keeps an independent state stream, avoiding a map copy for every
 * high-frequency progress update. A session's stream remains valid for active
 * collectors after [remove] publishes its terminal null value.
 */
class TerminalProgressStore {
    private val progressBySession = mutableMapOf<String, MutableStateFlow<TerminalProgress?>>()

    fun observe(session: TerminalSession): StateFlow<TerminalProgress?> =
        observe(session.mHandle, TerminalProgress.from(session))

    @Synchronized
    fun observe(sessionHandle: String, initial: TerminalProgress?): StateFlow<TerminalProgress?> =
        progressBySession.getOrPut(sessionHandle) { MutableStateFlow(initial) }

    fun update(session: TerminalSession) {
        update(session.mHandle, TerminalProgress.from(session))
    }

    @Synchronized
    fun update(sessionHandle: String, progress: TerminalProgress?) {
        progressBySession.getOrPut(sessionHandle) { MutableStateFlow(null) }.value = progress
    }

    fun remove(session: TerminalSession) {
        remove(session.mHandle)
    }

    @Synchronized
    fun remove(sessionHandle: String) {
        progressBySession.remove(sessionHandle)?.value = null
    }
}
