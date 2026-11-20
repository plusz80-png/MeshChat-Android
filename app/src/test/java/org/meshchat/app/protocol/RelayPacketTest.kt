package org.meshchat.app.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayPacketTest {
    @Test
    fun encodeDecodePreservesPacket() {
        val original = samplePacket()
        val decoded = RelayPacket.decode(original.encode())

        assertEquals(original, decoded)
        assertArrayEquals(original.packetId, decoded.packetId)
        assertArrayEquals(original.destinationToken, decoded.destinationToken)
        assertArrayEquals(original.sealedPayload, decoded.sealedPayload)
        assertEquals(original.expiresAtEpochSeconds, decoded.expiresAtEpochSeconds)
        assertEquals(original.remainingHops, decoded.remainingHops)
    }

    @Test
    fun forwardingChangesOnlyTheMutableHopCounter() {
        val original = samplePacket(remainingHops = 3)
        val forwarded = original.forwarded()

        assertNotNull(forwarded)
        assertEquals(2, forwarded!!.remainingHops)
        assertArrayEquals(original.authenticatedHeader(), forwarded.authenticatedHeader())
        assertFalse(original.encode().contentEquals(forwarded.encode()))
    }

    @Test
    fun packetCannotBeForwardedWhenHopBudgetIsZero() {
        assertNull(samplePacket(remainingHops = 0).forwarded())
    }

    @Test
    fun authenticatedHeaderBindsRoutingAndExpiryFields() {
        val original = samplePacket()
        val changedId = samplePacket(packetId = ByteArray(16) { (it + 1).toByte() })
        val changedDestination =
            samplePacket(destinationToken = ByteArray(16) { (it + 2).toByte() })
        val changedExpiry = samplePacket(expiresAt = 1_900_000_001L)

        assertFalse(original.authenticatedHeader().contentEquals(changedId.authenticatedHeader()))
        assertFalse(
            original.authenticatedHeader()
                .contentEquals(changedDestination.authenticatedHeader())
        )
        assertFalse(
            original.authenticatedHeader().contentEquals(changedExpiry.authenticatedHeader())
        )
    }

    @Test
    fun decoderRejectsTruncatedAndTrailingData() {
        val encoded = samplePacket().encode()

        assertThrows(IllegalArgumentException::class.java) {
            RelayPacket.decode(encoded.copyOf(encoded.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RelayPacket.decode(encoded + byteArrayOf(0))
        }
    }

    @Test
    fun decoderRejectsUnknownVersionAndExcessiveHopCount() {
        val encoded = samplePacket().encode()

        val unknownVersion = encoded.copyOf().also { it[4] = 2 }
        assertThrows(IllegalArgumentException::class.java) {
            RelayPacket.decode(unknownVersion)
        }

        val excessiveHops = encoded.copyOf().also { it[45] = 9 }
        assertThrows(IllegalArgumentException::class.java) {
            RelayPacket.decode(excessiveHops)
        }
    }

    @Test
    fun creationRejectsInvalidFields() {
        assertThrows(IllegalArgumentException::class.java) {
            RelayPacket.create(
                packetId = ByteArray(15),
                destinationToken = ByteArray(16),
                expiresAtEpochSeconds = 1_900_000_000L,
                remainingHops = 1,
                sealedPayload = ByteArray(16)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePacket(remainingHops = RelayPacket.MAX_HOPS + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            samplePacket(payload = ByteArray(15))
        }
    }

    @Test
    fun expiryIsDeterministicAtTheBoundary() {
        val packet = samplePacket(expiresAt = 1_900_000_000L)

        assertFalse(packet.isExpired(1_899_999_999L))
        assertEquals(true, packet.isExpired(1_900_000_000L))
    }

    private fun samplePacket(
        packetId: ByteArray = ByteArray(16) { it.toByte() },
        destinationToken: ByteArray = ByteArray(16) { (it + 16).toByte() },
        expiresAt: Long = 1_900_000_000L,
        remainingHops: Int = 5,
        payload: ByteArray = ByteArray(32) { (it + 1).toByte() }
    ) = RelayPacket.create(
        packetId = packetId,
        destinationToken = destinationToken,
        expiresAtEpochSeconds = expiresAt,
        remainingHops = remainingHops,
        sealedPayload = payload
    )
}
