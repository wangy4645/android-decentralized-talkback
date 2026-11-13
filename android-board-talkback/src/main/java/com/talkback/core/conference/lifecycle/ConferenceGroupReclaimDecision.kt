package com.talkback.core.conference.lifecycle

/**
 * GROUP reclaim vs live CONFERENCE ownership (A7-A).
 *
 * [accepted] only protects the *current* session identity. A stale object that is no
 * longer the live map entry is not establishing and may be reclaimed.
 */
object ConferenceGroupReclaimDecision {
    data class Snapshot(
        val candidateSessionId: String,
        val liveSessionId: String?,
        val accepted: Boolean,
        val isHostOwned: Boolean,
        val hostIceState: String?,
        val hasEstablishingInvite: Boolean,
        val operational: Boolean
    )

    enum class Verdict {
        SKIP_HOST_EXISTS,
        SKIP_OPERATIONAL,
        SKIP_ESTABLISHING,
        RECLAIM
    }

    fun verdict(snapshot: Snapshot): Verdict {
        if (snapshot.isHostOwned) return Verdict.SKIP_HOST_EXISTS
        if (snapshot.operational) return Verdict.SKIP_OPERATIONAL
        if (isEstablishing(snapshot)) return Verdict.SKIP_ESTABLISHING
        return Verdict.RECLAIM
    }

    fun isEstablishing(snapshot: Snapshot): Boolean {
        if (snapshot.liveSessionId == null || snapshot.liveSessionId != snapshot.candidateSessionId) {
            return false
        }
        if (snapshot.isHostOwned) return false
        if (!snapshot.accepted && !snapshot.hasEstablishingInvite) return false
        return when (snapshot.hostIceState?.uppercase()) {
            "CLOSED", "FAILED", "DISCONNECTED" -> false
            else -> true
        }
    }
}
