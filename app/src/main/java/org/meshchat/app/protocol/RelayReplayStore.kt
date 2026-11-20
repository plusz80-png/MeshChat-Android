package org.meshchat.app.protocol

enum class ReplayClaimResult {
    CLAIMED,
    ALREADY_SEEN,
    CAPACITY_REACHED
}

/**
 * Relay-local store for fingerprints of packets accepted for forwarding.
 * Implementations must make claimIfNew atomic across concurrent callers.
 * Only a SHA-256 fingerprint and expiry are stored, never packet bytes/plaintext.
 */
interface RelayReplayStore {
    fun claimIfNew(
        fingerprint: ByteArray,
        expiresAtEpochSeconds: Long,
        nowEpochSeconds: Long
    ): ReplayClaimResult
}

enum class RelayAdmission {
    ACCEPT,
    EXPIRED,
    INVALID_EXPIRY,
    HOP_LIMIT_REACHED,
    DUPLICATE,
    CAPACITY_REACHED
}

/** Applies bounded relay checks. It does not authenticate packets. */
class RelayReplayGuard(
    private val store: RelayReplayStore,
    private val maxLifetimeSeconds: Long = DEFAULT_MAX_LIFETIME_SECONDS
) {
    init {
        require(maxLifetimeSeconds > 0) { "maxLifetimeSeconds must be positive" }
    }

    fun admit(packet: RelayPacket, nowEpochSeconds: Long): RelayAdmission {
        require(nowEpochSeconds >= 0) { "nowEpochSeconds must not be negative" }
        if (packet.isExpired(nowEpochSeconds)) return RelayAdmission.EXPIRED
        if (packet.expiresAtEpochSeconds - nowEpochSeconds > maxLifetimeSeconds) {
            return RelayAdmission.INVALID_EXPIRY
        }
        if (packet.remainingHops == 0) return RelayAdmission.HOP_LIMIT_REACHED

        return when (
            store.claimIfNew(
                fingerprint = packet.replayFingerprint(),
                expiresAtEpochSeconds = packet.expiresAtEpochSeconds,
                nowEpochSeconds = nowEpochSeconds
            )
        ) {
            ReplayClaimResult.CLAIMED -> RelayAdmission.ACCEPT
            ReplayClaimResult.ALREADY_SEEN -> RelayAdmission.DUPLICATE
            ReplayClaimResult.CAPACITY_REACHED -> RelayAdmission.CAPACITY_REACHED
        }
    }

    companion object {
        /** Maximum relay queue lifetime in v1: seven days. */
        const val DEFAULT_MAX_LIFETIME_SECONDS = 7L * 24 * 60 * 60
    }
}
