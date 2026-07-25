package com.tagpulse.gateway.core.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tagpulse.gateway.core.GeoLocation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Serialization round-trip: an `Observation` with a full nested `sensor_data`
 * payload (incl. a fractional PID + a null-omitted PID) and a `GeoLocation`
 * survives enqueue → read-back with its payload/subject/source/location
 * reconstructing equal. Guards against locale/float-formatting drift.
 */
@RunWith(RobolectricTestRunner::class)
class OutboxSerializationTest {

    private lateinit var db: OutboxDatabase
    private lateinit var outbox: Outbox
    private val json = OutboxJson()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = OutboxDatabaseFactory.open(context, name = "serialization-test.db")
        outbox = Outbox(db.outboxDao(), json = json)
    }

    @After
    fun tearDown() {
        db.close()
        context.getDatabasePath("serialization-test.db").delete()
    }

    @Test
    fun `payload subject source and location round-trip unchanged`() = runBlocking {
        val original = OutboxFixtures.observation()

        val id = outbox.enqueue(original)
        val row = outbox.pending().single()
        assertEquals(id, row.id)

        val restored = OutboxMapper.toObservation(row, json)

        // Subject + source (the explicit "cheap hedge") reconstruct exactly.
        assertEquals(original.subject, restored.subject)
        assertEquals(original.source, restored.source)
        // Capture time survives (epoch-ms, no sub-ms in the fixture).
        assertEquals(original.timestamp, restored.timestamp)
        // Location value object round-trips.
        assertEquals(original.location, restored.location)
        // Whole payload map reconstructs equal (nested pids map included).
        assertEquals(original.payload, restored.payload)
    }

    @Test
    fun `fractional pid survives with a dot decimal regardless of locale`() = runBlocking {
        outbox.enqueue(OutboxFixtures.observation())
        val row = outbox.pending().single()

        // Locale guard: the persisted JSON must use a '.' decimal, never a ','.
        assertTrue(
            "fuel_level_pct must serialize with a dot decimal",
            row.payloadJson.contains("49.8") && !row.payloadJson.contains("49,8"),
        )

        @Suppress("UNCHECKED_CAST")
        val pids = OutboxMapper.toObservation(row, json).payload["pids"] as Map<String, Any?>
        val fuel = pids["fuel_level_pct"] as Number
        assertEquals(49.8, fuel.toDouble(), 0.0)
        // The null-omitted coolant PID stayed absent (not resurrected as null).
        assertTrue("coolant PID stays omitted", !pids.containsKey("coolant_temp_c"))
    }

    @Test
    fun `null location round-trips as null`() = runBlocking {
        outbox.enqueue(OutboxFixtures.observation(location = null))
        val row = outbox.pending().single()

        assertEquals(null, row.locationJson)
        assertEquals(null, OutboxMapper.toObservation(row, json).location)
    }

    @Test
    fun `location codec round-trips a GeoLocation directly`() {
        val loc = GeoLocation(latitude = -33.8688, longitude = 151.2093, accuracyMeters = null)
        val decoded = json.decodeLocation(json.encodeLocation(loc))
        assertNotNull(decoded)
        assertEquals(loc, decoded)
    }
}
