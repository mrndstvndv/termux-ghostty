package com.mrndtvndv.term.server

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Retains the latest finish event so a recreated UI cannot miss a disconnect.
 */
class SessionFinishedEvents {
    private val _events = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val flow: SharedFlow<String> = _events.asSharedFlow()

    fun emit(serverId: String) {
        _events.tryEmit(serverId)
    }
}
