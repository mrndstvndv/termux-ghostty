package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.ServerConfig
import com.termux.terminal.TerminalSession

/**
 * Owns all active (connected) Server instances.
 * One per saved server config. Max concurrent limit enforced.
 */
class ServerManager(
    private val serverFactory: ServerFactory,
    private val maxConcurrent: Int = 5,
) {
    private val activeServers = linkedMapOf<String, Server>() // insertion order

    val activeIds: Set<String> get() = activeServers.keys

    fun get(id: String): Server? = activeServers[id]

    fun isConnected(id: String): Boolean = activeServers.containsKey(id)

    /** Find the id of the server whose terminal session matches [session]. */
    fun serverIdForSession(session: TerminalSession): String? =
        activeServers.entries.firstOrNull { it.value.terminalSession === session }?.key

    /**
     * Connect to a server. If already connected, returns existing instance.
     * Throws if maxConcurrent would be exceeded and the server isn't already connected.
     */
    suspend fun connect(config: ServerConfig): Server {
        activeServers[config.id]?.let { return it }
        check(activeServers.size < maxConcurrent) {
            "Max $maxConcurrent concurrent connections reached. Disconnect one first."
        }
        val server = serverFactory.create(config)
        activeServers[config.id] = server
        return server
    }

    fun disconnect(id: String) {
        activeServers.remove(id)?.disconnect()
    }

    fun disconnectAll() {
        activeServers.values.forEach { it.disconnect() }
        activeServers.clear()
    }
}
