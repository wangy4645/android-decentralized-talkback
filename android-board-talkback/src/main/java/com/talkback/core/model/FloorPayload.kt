package com.talkback.core.model

import org.json.JSONObject

/**
 * JSON payload for floor control messages (FLOOR_REQUEST / FLOOR_GRANTED / FLOOR_DENY / FLOOR_RELEASE).
 */
data class FloorPayload(
    val floorVersion: Long,
    val floorEpoch: Long,
    val priority: EndpointPriority = EndpointPriority.NORMAL,
    val requesterKey: String = "",
    /** Observability-only correlation id; does not affect floor semantics. */
    val traceId: Long = 0L
) {
    fun encode(): String {
        val json = JSONObject()
            .put("floorVersion", floorVersion)
            .put("floorEpoch", floorEpoch)
            .put("priority", priority.name)
            .put("requesterKey", requesterKey)
        if (traceId > 0L) {
            json.put("traceId", traceId)
        }
        return json.toString()
    }

    companion object {
        fun decode(raw: String): FloorPayload {
            if (raw.isBlank()) return FloorPayload(0, 0)
            return runCatching {
                val json = JSONObject(raw)
                FloorPayload(
                    floorVersion = json.optLong("floorVersion", 0),
                    floorEpoch = json.optLong("floorEpoch", 0),
                    priority = runCatching {
                        EndpointPriority.valueOf(json.optString("priority", "NORMAL"))
                    }.getOrDefault(EndpointPriority.NORMAL),
                    requesterKey = json.optString("requesterKey", ""),
                    traceId = json.optLong("traceId", 0L)
                )
            }.getOrDefault(FloorPayload(0, 0))
        }

        fun forRequest(
            requester: EndpointAddress,
            floorVersion: Long,
            floorEpoch: Long,
            priority: EndpointPriority,
            traceId: Long = 0L
        ): FloorPayload = FloorPayload(
            floorVersion = floorVersion,
            floorEpoch = floorEpoch,
            priority = priority,
            requesterKey = requester.key,
            traceId = traceId
        )
    }
}
