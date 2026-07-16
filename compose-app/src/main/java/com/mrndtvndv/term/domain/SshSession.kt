package com.mrndtvndv.term.domain

import kotlinx.coroutines.flow.StateFlow

interface SshSession {
    val isConnected: StateFlow<Boolean>
    suspend fun connect(config: SshConfig)
    suspend fun authenticate(auth: SshAuth)
    suspend fun openShellChannel(termType: String, cols: Int, rows: Int): SshShellChannel
    fun disconnect()
}
