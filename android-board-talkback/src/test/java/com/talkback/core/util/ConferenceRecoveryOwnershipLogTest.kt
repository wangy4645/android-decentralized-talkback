package com.talkback.core.util

import com.talkback.core.session.EdgeRecoveryPhase
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConferenceRecoveryOwnershipLogTest {

    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        ConferenceRecoveryOwnershipLog.resetForTest { lines.add(it) }
    }

    @After
    fun tearDown() {
        ConferenceRecoveryOwnershipLog.resetForTest()
    }

    @Test
    fun mapPhaseToAttemptState_mapsFailedAndWaitIce() {
        assertEquals(
            ConferenceRecoveryOwnershipLog.AttemptObservationState.FAILED,
            ConferenceRecoveryOwnershipLog.mapPhaseToAttemptState(EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY)
        )
        assertEquals(
            ConferenceRecoveryOwnershipLog.AttemptObservationState.WAIT_ICE,
            ConferenceRecoveryOwnershipLog.mapPhaseToAttemptState(EdgeRecoveryPhase.ICE_RESTARTING)
        )
    }

    @Test
    fun emitSnapshot_includesDecisionAndMembershipFields() {
        ConferenceRecoveryOwnershipLog.emitSnapshot(
            reason = "failed_media_recovery",
            conferenceId = "sess-1",
            localModuleId = "M02",
            participantId = "M03",
            attemptLineage = ConferenceRecoveryOwnershipLog.AttemptLineageObservation(
                attemptId = 9L,
                attemptStartedAtMs = 1000L,
                attemptState = ConferenceRecoveryOwnershipLog.AttemptObservationState.FAILED,
                phase = EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY,
                mediaRestored = false,
                obligationOpen = true,
                pendingCompletion = false
            ),
            membershipObservation = ConferenceRecoveryOwnershipLog.ParticipantMembershipObservation(
                participantId = "M03",
                observedMembershipState = ConferenceRecoveryOwnershipLog.MembershipObservationState.JOINED,
                observedAtMs = 2000L,
                observedRosterEpoch = 3L,
                observedRosterOwner = "M02"
            ),
            membershipMutationDecision = ConferenceRecoveryOwnershipLog.MembershipMutationDecisionSnapshot(
                type = ConferenceRecoveryOwnershipLog.MembershipMutationDecisionType.PRUNE,
                reason = "AUTHORITY_PRUNE:AUTHORITY_PRUNE",
                sourceAttemptId = 9L,
                decisionAtMs = 2500L
            )
        )
        val line = lines.single()
        assertTrue(line.contains("CONFERENCE_RECOVERY_OWNERSHIP_SNAPSHOT"))
        assertTrue(line.contains("attemptId=9"))
        assertTrue(line.contains("attemptState=FAILED"))
        assertTrue(line.contains("observedMembershipState=JOINED"))
        assertTrue(line.contains("decisionType=PRUNE"))
        assertTrue(line.contains("sourceAttemptId=9"))
    }

    @Test
    fun observeParticipantMembership_joinedWhenInMemberModules() {
        val local = EndpointAddress(ModuleId("M02"), EndpointId("E02"))
        val session = TalkbackSession("sess-1", SessionType.CONFERENCE, local, null).apply {
            accepted = true
            rosterEpoch = 2L
            memberModules.add(ModuleId("M03"))
        }
        val obs = ConferenceRecoveryOwnershipLog.observeParticipantMembership(
            session = session,
            participantId = "M03",
            rosterOwner = "M02"
        )
        assertEquals(
            ConferenceRecoveryOwnershipLog.MembershipObservationState.JOINED,
            obs.observedMembershipState
        )
        assertEquals(2L, obs.observedRosterEpoch)
    }

    @Test
    fun emitRejoinResponse_logsBusyAsMembershipLivenessFact() {
        ConferenceRecoveryOwnershipLog.emitRejoinResponse(
            conferenceId = "sess-1",
            localModuleId = "M02",
            targetParticipantId = "M03",
            response = "BUSY",
            localMembershipBelief = ConferenceRecoveryOwnershipLog.MembershipObservationState.JOINED,
            remoteMembershipHint = "ALIVE",
            observedRosterEpoch = 4L
        )
        val line = lines.single()
        assertTrue(line.contains("CONFERENCE_REJOIN_RESPONSE"))
        assertTrue(line.contains("response=BUSY"))
        assertTrue(line.contains("localMembershipBelief=JOINED"))
        assertTrue(line.contains("remoteMembershipHint=ALIVE"))
    }
}
