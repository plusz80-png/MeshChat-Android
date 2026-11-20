package org.meshchat.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * BLE application-layer fragment for transporting a RelayPacket byte stream.
 *
 * [maxFrameBytes] is the maximum GATT characteristic payload (ATT MTU minus
 * the 3-byte ATT overhead), not the negotiated ATT MTU itself.
 * This class has no Android BLE dependencies.
 */
class BleFragment private constructor(
    val transferId: Long,
    val fragmentIndex: Int,
    val fragmentCount: Int,
    val totalLength: Int,
    val chunkSize: Int,
    payload: ByteArray
) {
    private val payloadBytes = payload.copyOf()
    val payload: ByteArray get() = payloadBytes.copyOf()

    init {
        require(transferId in 1L..MAX_TRANSFER_ID) { "transferId must be a non-zero uint32" }
        require(totalLength in 1..MAX_PACKET_BYTES) { "totalLength is outside the supported range" }
        require(chunkSize in MIN_CHUNK_BYTES..MAX_CHUNK_BYTES) { "chunkSize is outside the supported range" }
        require(fragmentCount in 1..MAX_FRAGMENT_COUNT) { "fragmentCount is outside the supported range" }
        require(fragmentIndex in 0 until fragmentCount) { "fragmentIndex is outside the fragment count" }
        require(fragmentCount == ceilDiv(totalLength, chunkSize)) { "fragmentCount does not match totalLength/chunkSize" }
        require(payloadBytes.size == expectedFragmentLength(totalLength, chunkSize, fragmentIndex)) {
            "Fragment payload length does not match its index"
        }
    }

    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + payloadBytes.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(MAGIC)
        buffer.put(VERSION.toByte())
        buffer.putInt(transferId.toInt())
        buffer.putShort(fragmentIndex.toShort())
        buffer.putShort(fragmentCount.toShort())
        buffer.putInt(totalLength)
        buffer.putShort(chunkSize.toShort())
        buffer.put(payloadBytes)
        return buffer.array()
    }

    companion object {
        const val VERSION = 1
        const val HEADER_BYTES = 17
        const val MIN_FRAME_BYTES = 20
        const val MIN_CHUNK_BYTES = MIN_FRAME_BYTES - HEADER_BYTES
        const val MAX_CHUNK_BYTES = 0xFFFF - HEADER_BYTES
        const val MAX_PACKET_BYTES = 65_586
        const val MAX_FRAGMENT_COUNT = 21_862
        const val MAX_FRAME_BYTES = 0xFFFF
        const val MAX_TRANSFER_ID = 0xFFFF_FFFFL

        private const val MAGIC: Short = 0x4D46 // ASCII "MF"
        private val random = SecureRandom()

        fun create(
            transferId: Long,
            fragmentIndex: Int,
            fragmentCount: Int,
            totalLength: Int,
            chunkSize: Int,
            payload: ByteArray
        ): BleFragment = BleFragment(
            transferId, fragmentIndex, fragmentCount, totalLength, chunkSize, payload
        )

        /** Creates a random 32-bit transfer identifier. Avoid reuse while a transfer is active. */
        fun newTransferId(): Long {
            var id: Long
            do {
                id = random.nextInt().toLong() and MAX_TRANSFER_ID
            } while (id == 0L)
            return id
        }

        fun decode(frame: ByteArray): BleFragment {
            require(frame.size in (HEADER_BYTES + 1)..MAX_FRAME_BYTES) { "Frame size is outside the supported range" }
            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
            require(buffer.short == MAGIC) { "Invalid BLE fragment magic" }
            val version = buffer.get().toInt() and 0xFF
            require(version == VERSION) { "Unsupported BLE fragment version: $version" }
            val transferId = buffer.int.toLong() and MAX_TRANSFER_ID
            val fragmentIndex = buffer.short.toInt() and 0xFFFF
            val fragmentCount = buffer.short.toInt() and 0xFFFF
            val totalLength = buffer.int
            val chunkSize = buffer.short.toInt() and 0xFFFF
            val payload = ByteArray(buffer.remaining())
            buffer.get(payload)
            return create(transferId, fragmentIndex, fragmentCount, totalLength, chunkSize, payload)
        }

        internal fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

        internal fun expectedFragmentLength(totalLength: Int, chunkSize: Int, index: Int): Int =
            minOf(chunkSize, totalLength - index * chunkSize)
    }
}

/** Splits one encoded RelayPacket into bounded GATT write payloads. */
object BleFragmenter {
    fun fragment(
        encodedPacket: ByteArray,
        maxFrameBytes: Int,
        transferId: Long = BleFragment.newTransferId()
    ): List<BleFragment> {
        require(encodedPacket.isNotEmpty()) { "Packet must not be empty" }
        require(encodedPacket.size <= BleFragment.MAX_PACKET_BYTES) { "Packet is too large" }
        require(maxFrameBytes in BleFragment.MIN_FRAME_BYTES..BleFragment.MAX_FRAME_BYTES) {
            "maxFrameBytes must be between ${BleFragment.MIN_FRAME_BYTES} and ${BleFragment.MAX_FRAME_BYTES}"
        }
        require(transferId in 1L..BleFragment.MAX_TRANSFER_ID) { "transferId must be a non-zero uint32" }

        val chunkSize = maxFrameBytes - BleFragment.HEADER_BYTES
        val count = BleFragment.ceilDiv(encodedPacket.size, chunkSize)
        require(count <= BleFragment.MAX_FRAGMENT_COUNT) { "Too many fragments" }

        return (0 until count).map { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, encodedPacket.size)
            BleFragment.create(
                transferId = transferId,
                fragmentIndex = index,
                fragmentCount = count,
                totalLength = encodedPacket.size,
                chunkSize = chunkSize,
                payload = encodedPacket.copyOfRange(start, end)
            )
        }
    }
}

sealed interface BleReassemblyResult {
    data object Pending : BleReassemblyResult
    data object DuplicateFragment : BleReassemblyResult
    data class Complete(val packetBytes: ByteArray) : BleReassemblyResult
    data object RejectedConflict : BleReassemblyResult
    data object RejectedCapacity : BleReassemblyResult
}

/**
 * Bounded out-of-order BLE reassembler.
 * Pass monotonic elapsedRealtime values in milliseconds. Expiration is explicit:
 * call [expire] regularly or before each [accept].
 */
class BleReassembler(
    private val maxActiveTransfers: Int = 4,
    private val maxBufferedBytes: Int = 4 * BleFragment.MAX_PACKET_BYTES,
    private val timeoutMillis: Long = 60_000,
    private val maxCompletedTransferIds: Int = 4_096
) {
    private data class Assembly(
        val count: Int,
        val totalLength: Int,
        val chunkSize: Int,
        val startedAtMillis: Long,
        val bytes: ByteArray,
        val received: BooleanArray,
        var receivedCount: Int = 0
    )

    private val active = LinkedHashMap<Long, Assembly>()
    private val completed = LinkedHashMap<Long, Long>()
    private var bufferedBytes = 0

    init {
        require(maxActiveTransfers > 0)
        require(maxBufferedBytes > 0)
        require(timeoutMillis > 0)
        require(maxCompletedTransferIds > 0)
    }

    fun accept(fragment: BleFragment, nowElapsedMillis: Long): BleReassemblyResult {
        require(nowElapsedMillis >= 0) { "nowElapsedMillis must not be negative" }
        expire(nowElapsedMillis)

        if (completed.containsKey(fragment.transferId)) return BleReassemblyResult.DuplicateFragment

        var assembly = active[fragment.transferId]
        if (assembly == null) {
            if (active.size >= maxActiveTransfers ||
                fragment.totalLength > maxBufferedBytes ||
                bufferedBytes + fragment.totalLength > maxBufferedBytes
            ) return BleReassemblyResult.RejectedCapacity

            assembly = Assembly(
                count = fragment.fragmentCount,
                totalLength = fragment.totalLength,
                chunkSize = fragment.chunkSize,
                startedAtMillis = nowElapsedMillis,
                bytes = ByteArray(fragment.totalLength),
                received = BooleanArray(fragment.fragmentCount)
            )
            active[fragment.transferId] = assembly
            bufferedBytes += fragment.totalLength
        } else if (
            assembly.count != fragment.fragmentCount ||
            assembly.totalLength != fragment.totalLength ||
            assembly.chunkSize != fragment.chunkSize
        ) {
            removeActive(fragment.transferId)
            return BleReassemblyResult.RejectedConflict
        }

        val offset = fragment.fragmentIndex * assembly.chunkSize
        val bytes = fragment.payload
        if (assembly.received[fragment.fragmentIndex]) {
            val identical = assembly.bytes.copyOfRange(offset, offset + bytes.size).contentEquals(bytes)
            if (!identical) {
                removeActive(fragment.transferId)
                return BleReassemblyResult.RejectedConflict
            }
            return BleReassemblyResult.DuplicateFragment
        }

        bytes.copyInto(assembly.bytes, offset)
        assembly.received[fragment.fragmentIndex] = true
        assembly.receivedCount++

        if (assembly.receivedCount != assembly.count) return BleReassemblyResult.Pending

        val result = assembly.bytes
        removeActive(fragment.transferId)
        completed[fragment.transferId] = nowElapsedMillis
        while (completed.size > maxCompletedTransferIds) {
            val oldestId = completed.keys.first()
            completed.remove(oldestId)
        }
        return BleReassemblyResult.Complete(result)
    }

    /** Removes incomplete and completed transfer IDs that have timed out. */
    fun expire(nowElapsedMillis: Long): Int {
        require(nowElapsedMillis >= 0) { "nowElapsedMillis must not be negative" }
        val expiredActive = active.filterValues {
            nowElapsedMillis >= it.startedAtMillis &&
                nowElapsedMillis - it.startedAtMillis >= timeoutMillis
        }.keys.toList()
        expiredActive.forEach(::removeActive)

        val expiredCompleted = completed.filterValues {
            nowElapsedMillis >= it && nowElapsedMillis - it >= timeoutMillis
        }.keys.toList()
        expiredCompleted.forEach(completed::remove)
        return expiredActive.size
    }

    fun cancel(transferId: Long): Boolean = removeActive(transferId)

    fun activeTransferCount(): Int = active.size

    fun bufferedByteCount(): Int = bufferedBytes

    private fun removeActive(transferId: Long): Boolean {
        val assembly = active.remove(transferId) ?: return false
        bufferedBytes -= assembly.totalLength
        return true
    }
}
