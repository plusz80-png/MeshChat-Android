package org.meshchat.app.protocol

import java.security.MessageDigest

/**
 * Stable identifier for replay suppression at an individual relay.
 * The mutable hop counter is excluded; the immutable header and sealed payload
 * are included. This hash is not an authenticator.
 */
fun RelayPacket.replayFingerprint(): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(authenticatedHeader())
    digest.update(sealedPayload)
    return digest.digest()
}
