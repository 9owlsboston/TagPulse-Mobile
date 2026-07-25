package com.tagpulse.gateway.core.outbox

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * The gateway core's durable store. Currently a single [OutboxItem] table; future
 * modalities reuse this same DB (plan §3 — the outbox is core, not driver code).
 *
 * Opened **file-backed** (see [OutboxDatabaseFactory]) so queued items survive a
 * process restart — the A4 guarantee.
 */
@Database(
    entities = [OutboxItem::class],
    version = 1,
    exportSchema = false,
)
abstract class OutboxDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
}

/**
 * Opens the [OutboxDatabase] against a **file on disk** (never in-memory), which
 * is what makes the outbox restart-safe (A4): after process death, reopening the
 * same file recovers every `PENDING` row.
 */
object OutboxDatabaseFactory {

    /** Default on-disk database file name (under the app's databases dir). */
    const val DEFAULT_NAME: String = "gateway-outbox.db"

    /** Open (or create) the DB by [name], resolved under the app's databases dir. */
    fun open(context: Context, name: String = DEFAULT_NAME): OutboxDatabase =
        Room.databaseBuilder(context.applicationContext, OutboxDatabase::class.java, name)
            .build()

    /**
     * Open (or create) the DB at an explicit [databaseFile]. Useful for tests that
     * pin the DB into a temp directory to exercise the close → reopen restart path.
     */
    fun openAt(context: Context, databaseFile: File): OutboxDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            OutboxDatabase::class.java,
            databaseFile.absolutePath,
        ).build()
}
