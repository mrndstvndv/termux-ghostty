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
        // disconnectAll() emits one event per server in a burst; keep room for
        // maxConcurrent (5) plus natural finishes without dropping.
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val flow: SharedFlow<String> = _events.asSharedFlow()

    fun emit(serverId: String) {
        _events.tryEmit(serverId)
    }
}
