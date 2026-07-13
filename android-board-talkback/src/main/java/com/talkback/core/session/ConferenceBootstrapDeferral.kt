package com.talkback.core.session

/**
 * MeetingStart-only full-mesh deferral (S3 / host-link bootstrap).
 * ADR-0021 R24-A: mid-meeting edge recovery residency MUST NOT use this path.
 */
object ConferenceBootstrapDeferral {
    /**
     * @return true only when a participant is still in first-time host-link bootstrap
     * and host ICE is not yet connected.
     */
    fun shouldDeferFullMesh(
        isParticipantConference: Boolean,
        edgeAnyRecovering: Boolean,
        edgeAnyFailedMediaRecovery: Boolean,
        hostIceConnected: Boolean
    ): Boolean {
        if (!isParticipantConference) return false
        if (edgeAnyRecovering || edgeAnyFailedMediaRecovery) return false
        return !hostIceConnected
    }
}
