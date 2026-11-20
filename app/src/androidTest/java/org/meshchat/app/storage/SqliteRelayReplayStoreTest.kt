package org.meshchat.app.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.meshchat.app.protocol.ReplayClaimResult
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SqliteRelayReplayStoreTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun replayRecordSurvivesStoreRecreation() {
        withDatabase { name ->
            val fingerprint = ByteArray(32) { it.toByte() }
            val first = SqliteRelayReplayStore(context, name, maxEntries = 10)
            assertEquals(ReplayClaimResult.CLAIMED, first.claimIfNew(fingerprint, EXPIRY, NOW))
            first.close()

            val reopened = SqliteRelayReplayStore(context, name, maxEntries = 10)
            assertEquals(
                ReplayClaimResult.ALREADY_SEEN,
                reopened.claimIfNew(fingerprint, EXPIRY, NOW)
            )
            reopened.close()
        }
    }

    @Test
    fun expiredRowsArePrunedAndCapacityFailsClosed() {
        withDatabase { name ->
            val store = SqliteRelayReplayStore(context, name, maxEntries = 1)
            assertEquals(
                ReplayClaimResult.CLAIMED,
                store.claimIfNew(ByteArray(32) { 1 }, NOW + 10, NOW)
            )
            assertEquals(
                ReplayClaimResult.CLAIMED,
                store.claimIfNew(ByteArray(32) { 2 }, NOW + 20, NOW + 10)
            )
            assertEquals(
                ReplayClaimResult.CAPACITY_REACHED,
                store.claimIfNew(ByteArray(32) { 3 }, NOW + 30, NOW + 10)
            )
            store.close()
        }
    }

    @Test
    fun concurrentClaimsAcceptOnlyOneCopy() {
        withDatabase { name ->
            val store = SqliteRelayReplayStore(context, name, maxEntries = 10)
            val gate = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(6)
            try {
                val tasks = (1..6).map {
                    executor.submit<ReplayClaimResult> {
                        gate.await()
                        store.claimIfNew(ByteArray(32) { 7 }, EXPIRY, NOW)
                    }
                }
                gate.countDown()
                val results = tasks.map { it.get(15, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it == ReplayClaimResult.CLAIMED })
                assertEquals(5, results.count { it == ReplayClaimResult.ALREADY_SEEN })
            } finally {
                executor.shutdownNow()
                store.close()
            }
        }
    }

    private inline fun withDatabase(block: (String) -> Unit) {
        val name = "meshchat-replay-test-" + UUID.randomUUID().toString() + ".db"
        try {
            block(name)
        } finally {
            context.deleteDatabase(name)
        }
    }

    companion object {
        private const val NOW = 1_900_000_000L
        private const val EXPIRY = NOW + 3_600
    }
}
