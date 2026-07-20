package com.mrndtvndv.term.domain

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val auth: AuthType? = null,
    val herdrEnabled: Boolean = false,
    val isLocal: Boolean = false,
    val startupCommand: String? = null,
) {
    /** Unique stable key for SharedPreferences lookups */
    val prefsKey: String get() = if (isLocal) {
        "${id}_local"
    } else {
        "${id}_${username}_${host}"
    }

    /** Terminal type string sent to the SSH server */
    val termType: String get() = if (herdrEnabled) "xterm-ghostty" else "xterm-256color"
}

@Serializable
sealed interface AuthType {
    @Serializable
    data class Password(val password: String) : AuthType

    @Serializable
    data class PublicKey(
        val privateKeyPem: String,
        val passphrase: String? = null,
    ) : AuthType
}
