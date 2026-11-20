package org.meshchat.app.storage

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.meshchat.app.protocol.ReplayClaimResult
import org.meshchat.app.protocol.RelayReplayStore

/**
 * Persistent, bounded replay store for volunteer relay nodes.
 * Stores only a SHA-256 fingerprint and expiry, never packet bytes, message
 * bodies, E2EE keys, or MLS state. Claims are serialized in a SQLite transaction.
 */
class SqliteRelayReplayStore(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) : SQLiteOpenHelper(
    context.applicationContext,
    databaseName,
    null,
    DATABASE_VERSION
), RelayReplayStore {

    init {
        require(databaseName.isNotBlank()) { "databaseName must not be blank" }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    override fun claimIfNew(
        fingerprint: ByteArray,
        expiresAtEpochSeconds: Long,
        nowEpochSeconds: Long
    ): ReplayClaimResult {
        require(fingerprint.size == FINGERPRINT_LENGTH) {
            "fingerprint must be a SHA-256 digest"
        }
        require(nowEpochSeconds >= 0) { "nowEpochSeconds must not be negative" }
        require(expiresAtEpochSeconds > nowEpochSeconds) {
            "Only unexpired packets can be claimed"
        }

        val key = fingerprint.toHex()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                TABLE_REPLAY,
                "$COLUMN_EXPIRES_AT <= ?",
                arrayOf(nowEpochSeconds.toString())
            )

            val alreadySeen = db.query(
                TABLE_REPLAY,
                arrayOf(COLUMN_FINGERPRINT),
                "$COLUMN_FINGERPRINT = ?",
                arrayOf(key),
                null,
                null,
                null,
                "1"
            ).use { cursor -> cursor.moveToFirst() }

            if (alreadySeen) {
                db.setTransactionSuccessful()
                return ReplayClaimResult.ALREADY_SEEN
            }

            val count = DatabaseUtils.longForQuery(
                db,
                "SELECT COUNT(*) FROM $TABLE_REPLAY",
                null
            )
            if (count >= maxEntries.toLong()) {
                // Fail closed: do not evict an unexpired replay record to make room.
                db.setTransactionSuccessful()
                return ReplayClaimResult.CAPACITY_REACHED
            }

            val values = ContentValues().apply {
                put(COLUMN_FINGERPRINT, key)
                put(COLUMN_EXPIRES_AT, expiresAtEpochSeconds)
            }
            val insertedRow = db.insertWithOnConflict(
                TABLE_REPLAY,
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
            )

            db.setTransactionSuccessful()
            return if (insertedRow == -1L) {
                ReplayClaimResult.ALREADY_SEEN
            } else {
                ReplayClaimResult.CLAIMED
            }
        } finally {
            db.endTransaction()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_REPLAY (
                $COLUMN_FINGERPRINT TEXT NOT NULL PRIMARY KEY,
                $COLUMN_EXPIRES_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX index_relay_replay_expires_at " +
                "ON $TABLE_REPLAY($COLUMN_EXPIRES_AT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema changes must add an explicit migration here.
        // Do not drop live replay records during an application upgrade.
    }

    private fun ByteArray.toHex(): String {
        val alphabet = "0123456789abcdef"
        return buildString(size * 2) {
            for (byte in this@toHex) {
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
    }

    companion object {
        const val DEFAULT_DATABASE_NAME = "meshchat-relay-replay.db"
        const val DEFAULT_MAX_ENTRIES = 20_000

        private const val DATABASE_VERSION = 1
        private const val TABLE_REPLAY = "relay_replay"
        private const val COLUMN_FINGERPRINT = "fingerprint"
        private const val COLUMN_EXPIRES_AT = "expires_at"
        private const val FINGERPRINT_LENGTH = 32
    }
}
