package com.talkback.core.session

/**
 * Phase 4-3 harness — shadow validation against seeded authority reference (no production wiring).
 */
class MeetingRuntimeShadowHarness {
    val authority = ConferenceTopologyAuthority()
    val validator = MeetingRuntimeShadowValidator()

    fun seedAuthorityEightNodeJoin(
        conferenceId: String = MeetingRuntimeObservationFixture.CONFERENCE_ID
    ): ConferenceTopologySnapshot {
        authority.publishTopologyProjection(
            AnchorAdmissionInput(
                conferenceId = conferenceId,
                hostModuleId = "M01",
                anchorId = "M01",
                members = MeetingRuntimeObservationFixture.EIGHT_MEMBERS,
                meshGeneration = 1L,
                anchorEpoch = AnchorRanking.INITIAL_ANCHOR_EPOCH
            )
        )
        return authority.currentSnapshot(conferenceId)!!
    }

    fun seedAuthorityAfterLeave(
        conferenceId: String = MeetingRuntimeObservationFixture.CONFERENCE_ID
    ): ConferenceTopologySnapshot {
        seedAuthorityEightNodeJoin(conferenceId)
        val remaining = MeetingRuntimeObservationFixture.EIGHT_MEMBERS.filter { it != "M05" }
        authority.publishRosterEdgeChange(conferenceId, remaining)
        return authority.currentSnapshot(conferenceId)!!
    }

    fun rejoinAuthority(
        conferenceId: String = MeetingRuntimeObservationFixture.CONFERENCE_ID
    ): ConferenceTopologySnapshot {
        authority.publishRosterEdgeChange(conferenceId, MeetingRuntimeObservationFixture.EIGHT_MEMBERS)
        return authority.currentSnapshot(conferenceId)!!
    }

    fun runCleanJoinShadow(
        conferenceId: String = MeetingRuntimeObservationFixture.CONFERENCE_ID
    ): ShadowComparisonReport {
        seedAuthorityEightNodeJoin(conferenceId)
        val (report, _) = validator.runTopologyShadow(
            authority,
            MeetingRuntimeObservationFixture.cleanJoinObservations(conferenceId)
        )
        return report
    }

    fun currentSnapshot(conferenceId: String = MeetingRuntimeObservationFixture.CONFERENCE_ID) =
        authority.currentSnapshot(conferenceId)
}
