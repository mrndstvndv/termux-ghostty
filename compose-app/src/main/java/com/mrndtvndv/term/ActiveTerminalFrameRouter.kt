package com.mrndtvndv.term

/** Routes frame publications only to the backend bound to the same session identity. */
internal class ActiveTerminalFrameRouter<Session : Any, Backend : Any>(
    private val refresh: (Backend) -> Unit
) {
    private var activeSession: Session? = null
    private var activeBackend: Backend? = null

    /** Binds and immediately replays the selected session, including idle sessions. */
    fun bind(session: Session, backend: Backend) {
        activeSession = session
        activeBackend = backend
        refresh(backend)
    }

    /** Ignores delayed disposal from a backend that has already been replaced. */
    fun unbind(session: Session, backend: Backend) {
        if (activeSession !== session || activeBackend !== backend) return
        activeSession = null
        activeBackend = null
    }

    fun onFrameAvailable(session: Session) {
        if (activeSession !== session) return
        activeBackend?.let(refresh)
    }

    fun refreshActive() {
        activeBackend?.let(refresh)
    }
}
