package com.tagpulse.gateway.obdii

import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The OBD-II `sensor_data` snapshot — one on-demand read of the four MVE PIDs,
 * assembled by [com.tagpulse.gateway.obdii.elm.Elm327Session.readSnapshot] and
 * carried across the core/driver seam by [ObdiiDriver] (plan
 * `docs/design/obdii-mve-plan.md` §4).
 *
 * **Graceful per-PID failure:** each PID field is nullable. A PID that returned
 * `NO DATA` / an error / a malformed frame is simply left `null` — the snapshot
 * still lands with whatever decoded, and one bad PID never fails the whole read
 * (plan §4/§6). [toPayload] omits null PIDs entirely.
 *
 * @property capturedAt snapshot capture time (UTC); rendered as ISO-8601 in the
 *   payload and used as the [com.tagpulse.gateway.core.Observation.timestamp].
 * @property rpm engine RPM (`010C`), or null if that PID failed.
 * @property speedKph vehicle speed in km/h (`010D`), or null.
 * @property coolantTempC coolant temperature in °C (`0105`, may be negative), or null.
 * @property fuelLevelPct fuel level percentage (`012F`), or null.
 * @property dongle best-effort adapter metadata (optional).
 * @property rawFrames the source ELM327 frames keyed by PID request (debug info).
 * @property includeRaw whether [toPayload] emits the `raw` block — debug-gated to
 *   stay within the footprint budget (plan §4: raw is "optional / droppable").
 */
data class ObdSnapshot(
    val capturedAt: Instant,
    val rpm: Int? = null,
    val speedKph: Int? = null,
    val coolantTempC: Int? = null,
    val fuelLevelPct: Float? = null,
    val dongle: DongleInfo? = null,
    val rawFrames: Map<String, String> = emptyMap(),
    val includeRaw: Boolean = false,
) {

    /**
     * Render this snapshot into the self-describing `sensor_data` JSON-shaped map
     * that becomes `Observation.payload` / `TagReadCreate.sensor_data` (plan §4).
     *
     * Only successfully-decoded PIDs appear under `pids`; the `raw` block is
     * emitted only when [includeRaw] is set (and non-empty). The map preserves
     * insertion order so the serialized JSON reads like the plan's example.
     */
    fun toPayload(): Map<String, Any?> {
        val pids = LinkedHashMap<String, Any?>()
        rpm?.let { pids["rpm"] = it }
        speedKph?.let { pids["speed_kph"] = it }
        coolantTempC?.let { pids["coolant_temp_c"] = it }
        fuelLevelPct?.let { pids["fuel_level_pct"] = it }

        val payload = LinkedHashMap<String, Any?>()
        payload["modality"] = MODALITY
        payload["protocol"] = PROTOCOL
        payload["captured_at"] = DateTimeFormatter.ISO_INSTANT.format(capturedAt)
        payload["pids"] = pids

        dongle?.toMap()?.takeIf { it.isNotEmpty() }?.let { payload["dongle"] = it }
        if (includeRaw && rawFrames.isNotEmpty()) {
            payload["raw"] = LinkedHashMap(rawFrames)
        }
        return payload
    }

    /**
     * Flatten to the neutral `Map<String, String>` the seam's
     * [com.tagpulse.gateway.core.DriverReading] carries. Inverse of
     * [fromAttributes]; kept symmetric so [ObdiiDriver.normalize] can reconstruct
     * losslessly (the seam is intentionally string-typed — plan §3).
     */
    fun toAttributes(): Map<String, String> {
        val attrs = LinkedHashMap<String, String>()
        attrs[KEY_MODALITY] = MODALITY
        attrs[KEY_PROTOCOL] = PROTOCOL
        attrs[KEY_CAPTURED_AT] = DateTimeFormatter.ISO_INSTANT.format(capturedAt)
        rpm?.let { attrs["${PID_PREFIX}rpm"] = it.toString() }
        speedKph?.let { attrs["${PID_PREFIX}speed_kph"] = it.toString() }
        coolantTempC?.let { attrs["${PID_PREFIX}coolant_temp_c"] = it.toString() }
        fuelLevelPct?.let { attrs["${PID_PREFIX}fuel_level_pct"] = it.toString() }
        dongle?.let { d ->
            d.bleName?.let { attrs["${DONGLE_PREFIX}ble_name"] = it }
            d.adapter?.let { attrs["${DONGLE_PREFIX}adapter"] = it }
            d.elmVersion?.let { attrs["${DONGLE_PREFIX}elm_version"] = it }
        }
        attrs[KEY_INCLUDE_RAW] = includeRaw.toString()
        for ((pid, frame) in rawFrames) attrs["$RAW_PREFIX$pid"] = frame
        return attrs
    }

    /** Best-effort dongle metadata; every field optional (plan §4). */
    data class DongleInfo(
        val bleName: String? = null,
        val adapter: String? = null,
        val elmVersion: String? = null,
    ) {
        fun toMap(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            bleName?.let { map["ble_name"] = it }
            adapter?.let { map["adapter"] = it }
            elmVersion?.let { map["elm_version"] = it }
            return map
        }
    }

    companion object {
        const val MODALITY = "obdii"
        const val PROTOCOL = "elm327/j1979"

        private const val KEY_MODALITY = "modality"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_CAPTURED_AT = "captured_at"
        private const val KEY_INCLUDE_RAW = "include_raw"
        private const val PID_PREFIX = "pids."
        private const val DONGLE_PREFIX = "dongle."
        private const val RAW_PREFIX = "raw."

        /**
         * Reconstruct a snapshot from the flat seam attributes produced by
         * [toAttributes] — the inverse used by [ObdiiDriver.normalize].
         */
        fun fromAttributes(attrs: Map<String, String>): ObdSnapshot {
            val dongle = DongleInfo(
                bleName = attrs["${DONGLE_PREFIX}ble_name"],
                adapter = attrs["${DONGLE_PREFIX}adapter"],
                elmVersion = attrs["${DONGLE_PREFIX}elm_version"],
            ).takeIf { it != DongleInfo() }

            val rawFrames = attrs
                .filterKeys { it.startsWith(RAW_PREFIX) }
                .mapKeys { it.key.removePrefix(RAW_PREFIX) }

            return ObdSnapshot(
                capturedAt = Instant.parse(attrs.getValue(KEY_CAPTURED_AT)),
                rpm = attrs["${PID_PREFIX}rpm"]?.toInt(),
                speedKph = attrs["${PID_PREFIX}speed_kph"]?.toInt(),
                coolantTempC = attrs["${PID_PREFIX}coolant_temp_c"]?.toInt(),
                fuelLevelPct = attrs["${PID_PREFIX}fuel_level_pct"]?.toFloat(),
                dongle = dongle,
                rawFrames = rawFrames,
                includeRaw = attrs[KEY_INCLUDE_RAW]?.toBooleanStrictOrNull() ?: false,
            )
        }
    }
}
