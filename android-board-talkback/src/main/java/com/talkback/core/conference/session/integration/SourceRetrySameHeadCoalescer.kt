package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.Profile01MembershipGeneration
import com.talkback.core.conference.session.profile01.wire.toHexLower
import java.util.concurrent.ConcurrentHashMap

/**
 * SOURCE_RETRY_SAME_HEAD_COALESCING — drop equivalent SOURCE retries for one
 * session + membership head before they occupy the coordinator again.
 *
 * Only coalesces after a settled [SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP]
 * (or while that attempt is in flight). BUILT / ALREADY_BUILT paths still run
 * so a later source-generation advance at the same membership head is not swallowed.
 */
class SourceRetrySameHeadCoalescer {
    data class HeadKey(
        val sessionId: String,
        val localModuleId: String,
        val mediaKeyEpoch: Long,
        val membershipVersion: Long,
        val headDigestHex: String,
    )

    private data class State(
        var inFlight: Boolean = false,
        var followUpPending: Boolean = false,
        var lastGap: Boolean = false,
        var coalescedCount: Long = 0L,
        var coalescedLogged: Boolean = false,
    )

    private val byKey = ConcurrentHashMap<HeadKey, State>()

    fun shouldCoalesce(key: HeadKey): Boolean {
        val state = byKey[key] ?: return false
        if (state.inFlight) {
            state.followUpPending = true
            state.coalescedCount += 1
            return true
        }
        if (state.lastGap) {
            state.coalescedCount += 1
            return true
        }
        return false
    }

    fun begin(key: HeadKey) {
        val state = byKey.getOrPut(key) { State() }
        state.inFlight = true
        state.followUpPending = false
    }

    /**
     * @return true if one follow-up attempt must run because a redundant trigger
     * arrived while [key] was in flight. Follow-up itself does not grant another.
     */
    fun complete(
        key: HeadKey,
        outcome: SourceOriginBuildOutcome,
        grantFollowUp: Boolean,
    ): Boolean {
        val state = byKey.getOrPut(key) { State() }
        state.inFlight = false
        state.lastGap = outcome == SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP
        val runFollowUp = grantFollowUp && state.followUpPending
        state.followUpPending = false
        if (!state.lastGap) {
            state.coalescedCount = 0
            state.coalescedLogged = false
        }
        return runFollowUp
    }

    fun shouldLogCoalesced(key: HeadKey): Boolean {
        val state = byKey[key] ?: return false
        if (state.coalescedLogged) return false
        if (state.coalescedCount <= 0) return false
        state.coalescedLogged = true
        return true
    }

    fun coalescedCount(key: HeadKey): Long = byKey[key]?.coalescedCount ?: 0L

    fun settledGap(key: HeadKey): Boolean = byKey[key]?.lastGap == true && byKey[key]?.inFlight != true

    fun clearSession(sessionId: String) {
        byKey.keys.removeIf { it.sessionId == sessionId }
    }

    companion object {
        const val NO_CONVERGENCE_DIGEST = "NO_CONVERGENCE"

        fun headKey(
            sessionId: String,
            localModuleId: String,
            generation: Profile01MembershipGeneration?,
        ): HeadKey {
            if (generation == null) {
                return HeadKey(
                    sessionId = sessionId,
                    localModuleId = localModuleId,
                    mediaKeyEpoch = SourceOriginEligibilityObligationStore.UNKNOWN_MEDIA_KEY_EPOCH,
                    membershipVersion = SourceOriginEligibilityObligationStore.UNKNOWN_MEMBERSHIP_VERSION,
                    headDigestHex = NO_CONVERGENCE_DIGEST,
                )
            }
            return HeadKey(
                sessionId = sessionId,
                localModuleId = localModuleId,
                mediaKeyEpoch = generation.mediaKeyEpoch,
                membershipVersion = generation.membershipVersion,
                headDigestHex = generation.generationFactDigest.toHexLower(),
            )
        }
    }
}
