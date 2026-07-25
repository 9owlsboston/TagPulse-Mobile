package com.tagpulse.gateway.core.outbox

import com.tagpulse.gateway.core.Modality
import com.tagpulse.gateway.core.Observation
import com.tagpulse.gateway.core.Source
import com.tagpulse.gateway.core.Subject
import com.tagpulse.gateway.core.SubjectKind
import java.time.Instant

/**
 * Pure mapping between the core's [Observation] and the persisted [OutboxItem].
 *
 * Kept separate from both the entity (which stays a plain data holder) and the
 * [Outbox] service so the round-trip is independently testable and reusable by the
 * M4 drainer (item → Observation → `TagReadCreate`).
 */
object OutboxMapper {

    /**
     * Build a fresh **`PENDING`** row from an [observation].
     *
     * @param createdAt insertion time (epoch ms), typically "now".
     * @param json the codec used to render `payload`/`location` to their columns.
     */
    fun toItem(observation: Observation, createdAt: Long, json: OutboxJson): OutboxItem =
        OutboxItem(
            subjectKind = observation.subject.kind.name,
            subjectId = observation.subject.id,
            sourceModality = observation.source.modality.name,
            sourceGatewayDeviceId = observation.source.gatewayDeviceId,
            capturedAt = observation.timestamp.toEpochMilli(),
            payloadJson = json.encodePayload(observation.payload),
            locationJson = observation.location?.let { json.encodeLocation(it) },
            state = OutboxState.PENDING.name,
            attempts = 0,
            createdAt = createdAt,
        )

    /** Reconstruct the normalized [Observation] carried by this row. */
    fun toObservation(item: OutboxItem, json: OutboxJson): Observation =
        Observation(
            subject = Subject(kind = SubjectKind.valueOf(item.subjectKind), id = item.subjectId),
            source = Source(
                modality = Modality.valueOf(item.sourceModality),
                gatewayDeviceId = item.sourceGatewayDeviceId,
            ),
            timestamp = Instant.ofEpochMilli(item.capturedAt),
            payload = json.decodePayload(item.payloadJson),
            location = item.locationJson?.let { json.decodeLocation(it) },
        )
}

/** The [OutboxState] this row is in. */
val OutboxItem.outboxState: OutboxState get() = OutboxState.valueOf(state)
