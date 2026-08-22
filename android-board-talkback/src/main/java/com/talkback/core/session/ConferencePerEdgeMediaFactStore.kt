package com.talkback.core.session

/**
 * Session-scoped store for [PerEdgeMediaUsabilityFact]. Does not admit edges.
 */
class ConferencePerEdgeMediaFactStore {

    private val byConference =
        LinkedHashMap<String, LinkedHashMap<MediaEdge, PerEdgeMediaUsabilityFact>>()

    fun upsert(
        snapshot: ConferenceTopologySnapshot,
        fact: PerEdgeMediaUsabilityFact,
        nowMs: Long
    ) {
        if (!ConferencePerEdgeMediaFactContract.isValidFact(
                snapshot,
                fact,
                nowMs,
                ConferencePresenceProjector.DEFAULT_STALE_AFTER_MS
            )
        ) {
            return
        }
        val bucket = byConference.getOrPut(snapshot.conferenceId) { LinkedHashMap() }
        val previous = bucket[fact.edge]
        if (previous != null && previous.producedAtMs > fact.producedAtMs) return
        bucket[fact.edge] = fact
    }

    fun facts(conferenceId: String): List<PerEdgeMediaUsabilityFact> =
        byConference[conferenceId]?.values?.toList().orEmpty()

    fun clear(conferenceId: String) {
        byConference.remove(conferenceId)
    }
}
