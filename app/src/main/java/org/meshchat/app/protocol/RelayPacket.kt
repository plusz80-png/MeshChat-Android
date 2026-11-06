package org.meshchat.app.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A transport-neutral container for an already end-to-end-encrypted message.
 *
 * This class only frames and validates bytes. It does NOT encrypt, authenticate,
 * sign, identify peers, or provide replay protection. Callers must supply a
 * ciphertext produced by the eventual E2EE implementation.
 */
class RelayPacket private constructor(
    private val packetIdBytes: ByteArray,
    private val destinationTokenBytes: ByteArray,
    val expiresAtEpochSeconds: Long,
    val remainingHops: Int,
    private val sealedPayloadBytes: ByteArray
) {
    val packetId: ByteArray
        get() = packetIdBytes.copyOf()

    val destinationToken: ByteArray
        get() = destinationTokenBytes.copyOf()

    val sealedPayload: ByteArray
        get() = sealedPayloadBytes.copyOf()

    /** True when the packet has reached its expiry instant. */
    fun isExpired(nowEpochSeconds: Long): Boolean =
        nowEpochSeconds >= expiresAtEpochSeconds

    /**
     * Associated data for the future end-to-end AEAD operation.
     *
     * The hop counter is intentionally excluded because relays must decrement
     * it without possessing the recipient's encryption key. Packet ID, opaque
     * destination token and expiry are bound to the ciphertext and therefore
     * cannot be changed undetectably by a relay.
     */
    fun authenticatedHeader(): ByteArray {
        val domain = AAD_DOMAIN
        return ByteBuffer
            .allocate(domain.size + 1 + ID_LENGTH + ID_LENGTH + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(domain)
            .put(VERSION.toByte())
            .put(packetIdBytes)
            .put(destinationTokenBytes)
            .putLong(expiresAtEpochSeconds)
            .array()
    }

    /**
     * Returns a copy with one fewer forwarding hop, or null when forwarding
     * is exhausted. This is a best-effort loop/propagation limit, not a
     * cryptographic proof of the route.
     */
    fun forwarded(): RelayPacket? =
        if (remainingHops == 0) {
            null
        } else {
            create(
                packetId = packetIdBytes,
                destinationToken = destinationTokenBytes,
                expiresAtEpochSeconds = expiresAtEpochSeconds,
                remainingHops = remainingHops - 1,
                sealedPayload = sealedPayloadBytes
            )
        }

    fun encode(): ByteArray {
        val output = ByteArrayOutputStream(HEADER_SIZE + sealedPayloadBytes.size)
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeByte(VERSION)
            data.write(packetIdBytes)
            data.write(destinationTokenBytes)
            data.writeLong(expiresAtEpochSeconds)
            data.writeByte(remainingHops)
            data.writeInt(sealedPayloadBytes.size)
            data.write(sealedPayloadBytes)
        }
        return output.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RelayPacket) return false
        return packetIdBytes.contentEquals(other.packetIdBytes) &&
            destinationTokenBytes.contentEquals(other.destinationTokenBytes) &&
            expiresAtEpochSeconds == other.expiresAtEpochSeconds &&
            remainingHops == other.remainingHops &&
            sealedPayloadBytes.contentEquals(other.sealedPayloadBytes)
    }

    override fun hashCode(): Int {
        var result = packetIdBytes.contentHashCode()
        result = 31 * result + destinationTokenBytes.contentHashCode()
        result = 31 * result + expiresAtEpochSeconds.hashCode()
        result = 31 * result + remainingHops
        result = 31 * result + sealedPayloadBytes.contentHashCode()
        return result
    }

    companion object {
        const val VERSION = 1
        const val ID_LENGTH = 16
        const val MAX_HOPS = 8
        const val MIN_SEALED_PAYLOAD_BYTES = 16
        const val MAX_SEALED_PAYLOAD_BYTES = 64 * 1024

        private const val MAGIC = 0x4D434854 // ASCII "MCHT"
        private const val HEADER_SIZE = 4 + 1 + ID_LENGTH + ID_LENGTH + 8 + 1 + 4
        private const val MAX_WIRE_SIZE = HEADER_SIZE + MAX_SEALED_PAYLOAD_BYTES
        private val AAD_DOMAIN =
            "MESHCHAT-RELAY-AAD".toByteArray(Charsets.US_ASCII)

        fun create(
            packetId: ByteArray,
            destinationToken: ByteArray,
            expiresAtEpochSeconds: Long,
            remainingHops: Int,
            sealedPayload: ByteArray
        ): RelayPacket {
            require(packetId.size == ID_LENGTH) {
                "packetId must contain exactly $ID_LENGTH bytes"
            }
            require(destinationToken.size == ID_LENGTH) {
                "destinationToken must contain exactly $ID_LENGTH bytes"
            }
            require(expiresAtEpochSeconds > 0) {
                "expiresAtEpochSeconds must be a positive Unix timestamp"
            }
            require(remainingHops in 0..MAX_HOPS) {
                "remainingHops must be between 0 and $MAX_HOPS"
            }
            require(
                sealedPayload.size in MIN_SEALED_PAYLOAD_BYTES..MAX_SEALED_PAYLOAD_BYTES
            ) {
                "sealedPayload size is outside the supported range"
            }

            // Defensive copies prevent mutation after validation.
            return RelayPacket(
                packetIdBytes = packetId.copyOf(),
                destinationTokenBytes = destinationToken.copyOf(),
                expiresAtEpochSeconds = expiresAtEpochSeconds,
                remainingHops = remainingHops,
                sealedPayloadBytes = sealedPayload.copyOf()
            )
        }

        fun decode(encoded: ByteArray): RelayPacket {
            require(encoded.size in
                (HEADER_SIZE + MIN_SEALED_PAYLOAD_BYTES)..MAX_WIRE_SIZE
            ) {
                "Encoded packet size is outside the supported range"
            }

            try {
                val input = DataInputStream(ByteArrayInputStream(encoded))
                require(input.readInt() == MAGIC) { "Invalid packet magic" }

                val version = input.readUnsignedByte()
                require(version == VERSION) { "Unsupported packet version: $version" }

                val packetId = ByteArray(ID_LENGTH)
                input.readFully(packetId)

                val destinationToken = ByteArray(ID_LENGTH)
                input.readFully(destinationToken)

                val expiresAt = input.readLong()
                val remainingHops = input.readUnsignedByte()
                val payloadLength = input.readInt()

                require(payloadLength in
                    MIN_SEALED_PAYLOAD_BYTES..MAX_SEALED_PAYLOAD_BYTES
                ) {
                    "Invalid sealed payload length"
                }
                require(input.available() == payloadLength) {
                    "Packet length does not match its payload length"
                }

                val payload = ByteArray(payloadLength)
                input.readFully(payload)

                return create(
                    packetId = packetId,
                    destinationToken = destinationToken,
                    expiresAtEpochSeconds = expiresAt,
                    remainingHops = remainingHops,
                    sealedPayload = payload
                )
            } catch (e: EOFException) {
                throw IllegalArgumentException("Truncated relay packet", e)
            }
        }
    }
}
