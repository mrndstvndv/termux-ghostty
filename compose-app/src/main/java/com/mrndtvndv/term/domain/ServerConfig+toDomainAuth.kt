package com.mrndtvndv.term.domain

fun ServerConfig.toDomainAuth(): SshAuth = when (val a = auth) {
    is AuthType.Password -> SshAuth.Password(a.password.toCharArray())
    is AuthType.PublicKey -> SshAuth.PublicKey(
        privateKeyPem = a.privateKeyPem,
        passphrase = a.passphrase?.toCharArray(),
    )
    null -> throw IllegalStateException("Auth required for SSH connection; local shell should be handled before this call")
}
