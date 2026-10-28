package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId

/**
 * Phase 4-4 harness — real session observation source + shadow wiring (no production control).
 */
class RuntimeShadowWiringHarness {
    val authority = ConferenceTopologyAuthority()
    val validator = MeetingRuntimeShadowValidator()

    fun eightNodeSession(
        sessionId: String = MeetingRuntimeObservationFixture.CONFERENCE_ID
    ): TalkbackSession {
        val local = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        val remotes = MeetingRuntimeObservationFixture.EIGHT_MEMBERS.filter { it != "M01" }
        return TalkbackSession(sessionId, SessionType.CONFERENCE, local, "CH-L8").apply {
            accepted = true
            groupMembers = remotes.mapIndexed { index, remote ->
                EndpointAddress(ModuleId(remote), EndpointId("E0${index + 2}"))
            }
            remotes.forEach { remote ->
                meshCompletedModules.add(remote)
                participant(remote).media = MediaState.CONNECTING
            }
            remotes.take(5).forEach { remote ->
                participant(remote).media = MediaState.CONNECTED
            }
            anchorModuleId = ModuleId("M01")
            anchorEpoch = AnchorRanking.INITIAL_ANCHOR_EPOCH
            meshGeneration = 1L
        }
    }

    fun seedAuthorityForSession(session: TalkbackSession): ConferenceTopologySnapshot {
        val members = TalkbackSessionRuntimeObservationSource(session)
            .snapshotObservation()!!
            .membership.memberModuleIds
        authority.publishTopologyProjection(
            AnchorAdmissionInput(
                conferenceId = session.id,
                hostModuleId = "M01",
                anchorId = "M01",
                members = members,
                meshGeneration = 1L,
                anchorEpoch = AnchorRanking.INITIAL_ANCHOR_EPOCH
            )
        )
        return authority.currentSnapshot(session.id)!!
    }

    fun runShadow(session: TalkbackSession): RuntimeShadowWiringResult {
        seedAuthorityForSession(session)
        val source = TalkbackSessionRuntimeObservationSource(session)
        val timelineBefore = source.sessionTimelineSignature()
        val result = RuntimeShadowWiringContract.runShadowFromSource(
            source = source,
            authority = authority,
            validator = validator,
            timelineSignature = timelineBefore,
            includeRecoveryShadowForRemote = "M05"
        )!!
        return result
    }

    fun runShadowWithMismatch(session: TalkbackSession): RuntimeShadowWiringResult {
        seedAuthorityForSession(session)
        session.meshCompletedModules.add("M99")
        val source = TalkbackSessionRuntimeObservationSource(session)
        return RuntimeShadowWiringContract.runShadowFromSource(
            source = source,
            authority = authority,
            validator = validator,
            timelineSignature = source.sessionTimelineSignature()
        )!!
    }
}
