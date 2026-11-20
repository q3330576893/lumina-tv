package com.lumina.tv.pair

import java.security.MessageDigest

internal const val PAIRING_TOKEN_TTL_MS = 5 * 60 * 1000L

internal data class PairingEndpoint(val ip: String, val port: Int, val listenerGeneration: Long)

internal class PairingAdvertisement(
    val endpoint: PairingEndpoint,
    private val token: String,
    val expiresAt: Long,
) {
    val url = "http://${endpoint.ip}:${endpoint.port}/?token=$token"

    fun accepts(candidate: String, now: Long): Boolean = now < expiresAt && candidate.isNotBlank() &&
        MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))
}

internal fun refreshPairingAdvertisement(
    previous: PairingAdvertisement?,
    endpoint: PairingEndpoint?,
    now: Long,
    force: Boolean = false,
    issueToken: () -> String,
): PairingAdvertisement? = when {
    endpoint == null -> null
    !force && previous != null && previous.endpoint == endpoint && now < previous.expiresAt -> previous
    else -> PairingAdvertisement(endpoint, issueToken(), now + PAIRING_TOKEN_TTL_MS)
}
