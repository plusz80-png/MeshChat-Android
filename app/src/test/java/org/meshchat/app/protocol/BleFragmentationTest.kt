package org.meshchat.app.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BleFragmentationTest {
    @Test
    fun fragmentsAtMinimumGattWriteSizeAndReassemblesOutOfOrder() {
        val packet = ByteArray(127) { (it * 17).toByte() }
        val fragments = BleFragmenter.fragment(packet, maxFrameBytes = 20, transferId = 0x10203040L)
        assertTrue(fragments.size > 1)
        assertTrue(fragments.all { it.encode().size <= 20 })

        val reassembler = BleReassembler()
        var result: BleReassemblyResult = BleReassemblyResult.Pending
        fragments.asReversed().forEach { fragment ->
            result = reassembler.accept(BleFragment.decode(fragment.encode()), nowElapsedMillis = 100)
        }

        assertTrue(result is BleReassemblyResult.Complete)
        assertArrayEquals(packet, (result as BleReassemblyResult.Complete).packetBytes)
        assertEquals(0, reassembler.activeTransferCount())
        assertEquals(0, reassembler.bufferedByteCount())
        assertEquals(BleReassemblyResult.DuplicateFragment, reassembler.accept(fragments[0], 100))
    }

    @Test
    fun identicalDuplicateIsIgnoredButConflictingDuplicateDropsAssembly() {
        val fragments = BleFragmenter.fragment(ByteArray(60) { 3 }, 20, transferId = 77)
        val reassembler = BleReassembler()
        assertEquals(BleReassemblyResult.Pending, reassembler.accept(fragments[0], 10))
        assertEquals(BleReassemblyResult.DuplicateFragment, reassembler.accept(fragments[0], 11))

        val originalPayload = fragments[0].payload
        originalPayload[0] = (originalPayload[0].toInt() xor 0xFF).toByte()
        val conflicting = BleFragment.create(
            transferId = fragments[0].transferId,
            fragmentIndex = fragments[0].fragmentIndex,
            fragmentCount = fragments[0].fragmentCount,
            totalLength = fragments[0].totalLength,
            chunkSize = fragments[0].chunkSize,
            payload = originalPayload
        )
        assertEquals(BleReassemblyResult.RejectedConflict, reassembler.accept(conflicting, 12))
        assertEquals(0, reassembler.activeTransferCount())
        assertEquals(0, reassembler.bufferedByteCount())
    }

    @Test
    fun metadataMismatchRejectsAndReleasesReservedMemory() {
        val packet = ByteArray(60) { 5 }
        val first = BleFragmenter.fragment(packet, 20, transferId = 99)[0]
        val second = BleFragmenter.fragment(packet, 21, transferId = 99)[0]
        val reassembler = BleReassembler()
        assertEquals(BleReassemblyResult.Pending, reassembler.accept(first, 0))
        assertEquals(BleReassemblyResult.RejectedConflict, reassembler.accept(second, 1))
        assertEquals(0, reassembler.activeTransferCount())
        assertEquals(0, reassembler.bufferedByteCount())
    }

    @Test
    fun activeTransferAndByteLimitsAreEnforced() {
        val first = BleFragmenter.fragment(ByteArray(50), 20, transferId = 1)[0]
        val second = BleFragmenter.fragment(ByteArray(50), 20, transferId = 2)[0]
        val reassembler = BleReassembler(maxActiveTransfers = 1, maxBufferedBytes = 50)
        assertEquals(BleReassemblyResult.Pending, reassembler.accept(first, 100))
        assertEquals(BleReassemblyResult.RejectedCapacity, reassembler.accept(second, 100))
        assertEquals(1, reassembler.activeTransferCount())
        assertEquals(50, reassembler.bufferedByteCount())
    }

    @Test
    fun incompleteTransfersExpireAndFreeMemory() {
        val first = BleFragmenter.fragment(ByteArray(80), 20, transferId = 3)[0]
        val reassembler = BleReassembler(timeoutMillis = 1_000)
        assertEquals(BleReassemblyResult.Pending, reassembler.accept(first, 5_000))
        assertEquals(1, reassembler.expire(6_000))
        assertEquals(0, reassembler.activeTransferCount())
        assertEquals(0, reassembler.bufferedByteCount())
        assertFalse(reassembler.cancel(3))
    }

    @Test
    fun decoderRejectsTruncatedFrameAndInvalidTransferId() {
        val fragment = BleFragmenter.fragment(ByteArray(30), 20, transferId = 4)[0]
        assertThrows(IllegalArgumentException::class.java) {
            BleFragment.decode(fragment.encode().copyOf(10))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BleFragment.create(0, 0, 1, 1, 3, byteArrayOf(1))
        }
    }
    @Test
    fun concurrentFragmentsCompleteExactlyOnce() {
        val packet = ByteArray(256) { it.toByte() }
        val fragments = BleFragmenter.fragment(packet, maxFrameBytes = 40, transferId = 55)
        val reassembler = BleReassembler()
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = fragments.map { fragment ->
                executor.submit<BleReassemblyResult> {
                    gate.await()
                    reassembler.accept(fragment, nowElapsedMillis = 500)
                }
            }
            gate.countDown()
            val results = tasks.map { it.get(5, TimeUnit.SECONDS) }
            val completed = results.filterIsInstance<BleReassemblyResult.Complete>()
            assertEquals(1, completed.size)
            assertArrayEquals(packet, completed.single().packetBytes)
            assertEquals(0, reassembler.activeTransferCount())
            assertEquals(0, reassembler.bufferedByteCount())
        } finally {
            executor.shutdownNow()
        }
    }

}
