package com.mrndtvndv.term.domain

data class SshConfig(
    val host: String,
    val port: Int,
    val username: String,
    val connectionTimeoutMs: Int = 10000
)

sealed interface SshAuth {
    data class Password(val password: CharArray) : SshAuth {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Password
            return password.contentEquals(other.password)
        }

        override fun hashCode(): Int {
            return password.contentHashCode()
        }
    }

    data class PublicKey(
        val privateKeyPem: String,
        val passphrase: CharArray? = null
    ) : SshAuth {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as PublicKey
            if (privateKeyPem != other.privateKeyPem) return false
            if (passphrase != null) {
                if (other.passphrase == null) return false
                if (!passphrase.contentEquals(other.passphrase)) return false
            } else if (other.passphrase != null) return false
            return true
        }

        override fun hashCode(): Int {
            var result = privateKeyPem.hashCode()
            result = 31 * result + (passphrase?.contentHashCode() ?: 0)
            return result
        }
    }
}
