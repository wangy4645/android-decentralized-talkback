package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceParticipantDisplayState
import com.talkback.core.session.ConferenceParticipantViewState
import com.talkback.core.session.ConferencePresenceProjection
import com.talkback.core.session.CppMembership

/**
 * UI fuse: CPP presence + CFC-1 L-participant failure projection (CPP-RECONNECTING-SEMANTICS-IMPL).
 * Failure terminals are excluded from joining/recovering semantics; they map to DEGRADED at bind time.
 */
object ConferencePresenceUiFuse {

    fun classifiedFailureModuleIds(
        visibleParticipants: List<ConferenceParticipantViewState>,
    ): Set<String> = visibleParticipants
        .asSequence()
        .filter {
            it.displayState == ConferenceParticipantDisplayState.VISIBLE_EDGE_FAILED ||
                it.displayState == ConferenceParticipantDisplayState.VISIBLE_DOMAIN_BLOCKED
        }
        .map { it.moduleId }
        .toSet()

    fun joiningHint(
        projection: ConferencePresenceProjection,
        excludedFailureModuleIds: Set<String>,
        localCaptureBlocked: Boolean,
    ): String? {
        if (localCaptureBlocked) return "Microphone unavailable"
        val joining = projection.participants.filter {
            it.membership == CppMembership.JOINED &&
                !it.mediaConnected &&
                it.moduleId !in projection.recoveringPeers &&
                it.moduleId !in excludedFailureModuleIds
        }
        return when (joining.size) {
            0 -> null
            1 -> "${joining.single().moduleId} joining..."
            else -> "${joining.size} joining..."
        }
    }
}
