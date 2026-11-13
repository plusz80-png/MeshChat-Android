package org.meshchat.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayReplayGuardTest {
    @Test
    fun forwardingHopChangesDoNotChangeReplayFingerprint() {
        val original = samplePacket(remainingHops = 4)
        val forwarded = original.forwarded()!!
        assertTrue(original.replayFingerprint().contentEquals(forwarded.replayFingerprint()))
    }

    @Test
    fun changedCiphertextChangesReplayFingerprint() {
        val original = samplePacket()
        val changed = samplePacket(payload = ByteArray(32) { 99 })
        assertFalse(original.replayFingerprint().contentEquals(changed.replayFingerprint()))
    }

    @Test
    fun samePacketIsAcceptedOnlyOnceAfterHopCountChanges() {
        val guard = RelayReplayGuard(FakeReplayStore())
        val original = samplePacket(remainingHops = 4)
        assertEquals(RelayAdmission.ACCEPT, guard.admit(original, NOW))
        assertEquals(RelayAdmission.DUPLICATE, guard.admit(original.forwarded()!!, NOW))
    }

    @Test
    fun expiredPacketsAreRejectedBeforeStorage() {
        val store = FakeReplayStore()
        assertEquals(
            RelayAdmission.EXPIRED,
            RelayReplayGuard(store).admit(samplePacket(expiresAt = NOW), NOW)
        )
        assertEquals(0, store.size)
    }

    @Test
    fun excessiveLifetimeIsRejectedBeforeStorage() {
        val store = FakeReplayStore()
        val expiry = NOW + RelayReplayGuard.DEFAULT_MAX_LIFETIME_SECONDS + 1
        assertEquals(
            RelayAdmission.INVALID_EXPIRY,
            RelayReplayGuard(store).admit(samplePacket(expiresAt = expiry), NOW)
        )
        assertEquals(0, store.size)
    }

    @Test
    fun exhaustedHopBudgetIsNotQueuedForForwarding() {
        val store = FakeReplayStore()
        assertEquals(
            RelayAdmission.HOP_LIMIT_REACHED,
            RelayReplayGuard(store).admit(samplePacket(remainingHops = 0), NOW)
        )
        assertEquals(0, store.size)
    }

    @Test
    fun fullStoreFailsClosed() {
        val guard = RelayReplayGuard(FakeReplayStore(maxEntries = 1))
        assertEquals(RelayAdmission.ACCEPT, guard.admit(samplePacket(), NOW))
        assertEquals(
            RelayAdmission.CAPACITY_REACHED,
            guard.admit(
                samplePacket(packetId = ByteArray(16) { (it + 40).toByte() }),
                NOW
            )
        )
    }

    private fun samplePacket(
        packetId: ByteArray = ByteArray(16) { it.toByte() },
        destinationToken: ByteArray = ByteArray(16) { (it + 16).toByte() },
        expiresAt: Long = NOW + 3_600,
        remainingHops: Int = 3,
        payload: ByteArray = ByteArray(32) { (it + 1).toByte() }
    ) = RelayPacket.create(
        packetId = packetId,
        destinationToken = destinationToken,
        expiresAtEpochSeconds = expiresAt,
        remainingHops = remainingHops,
        sealedPayload = payload
    )

    private class FakeReplayStore(
        private val maxEntries: Int = 100
    ) : RelayReplayStore {
        private val entries = mutableMapOf<String, Long>()
        val size: Int get() = entries.size

        override fun claimIfNew(
            fingerprint: ByteArray,
            expiresAtEpochSeconds: Long,
            nowEpochSeconds: Long
        ): ReplayClaimResult {
            entries.entries.removeAll { it.value <= nowEpochSeconds }
            val key = fingerprint.joinToString("") { "%02x".format(it) }
            if (entries.containsKey(key)) return ReplayClaimResult.ALREADY_SEEN
            if (entries.size >= maxEntries) return ReplayClaimResult.CAPACITY_REACHED
            entries[key] = expiresAtEpochSeconds
            return ReplayClaimResult.CLAIMED
        }
    }

    companion object {
        private const val NOW = 1_900_000_000L
    }
}
