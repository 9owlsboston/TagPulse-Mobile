package com.tagpulse.gateway.core.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * **A4 — restart safety.** The faithful JVM analogue of "enqueue → kill process →
 * relaunch → item still pending": open a **file-backed** Room DB in a temp dir,
 * enqueue, **close** the DB (simulates process death — the in-memory Room/SQLite
 * handles are gone), then **reopen a brand-new DB instance on the same file** and
 * assert the item survived and is still `PENDING`.
 */
@RunWith(RobolectricTestRunner::class)
class OutboxRestartTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `enqueued item survives a close-and-reopen on the same db file`() = runBlocking {
        val dbFile = File(tempFolder.root, "restart-outbox.db")

        // --- First "process life": enqueue, then simulate death by closing the DB.
        val db1 = OutboxDatabaseFactory.openAt(context, dbFile)
        val id = Outbox(db1.outboxDao()).enqueue(OutboxFixtures.observation())
        assertTrue("insert returns a real row id", id > 0)
        db1.close()

        assertTrue("the DB is genuinely file-backed on disk", dbFile.exists())

        // --- "Relaunch": a fresh DB instance over the same file must recover it.
        val db2 = OutboxDatabaseFactory.openAt(context, dbFile)
        try {
            val outbox = Outbox(db2.outboxDao())
            val pending = outbox.pending()

            assertEquals("exactly the one enqueued item survived restart", 1, pending.size)
            val survived = pending.single()
            assertEquals(id, survived.id)
            assertEquals(OutboxState.PENDING, survived.outboxState)
            assertEquals(0, survived.attempts)
            assertEquals("vehicle-42", survived.subjectId)
            assertNotNull("payload persisted across restart", survived.payloadJson)
        } finally {
            db2.close()
        }
    }
}
