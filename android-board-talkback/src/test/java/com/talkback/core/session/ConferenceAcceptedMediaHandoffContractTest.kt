package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceAcceptedMediaHandoffContractTest {

    private val members4 = listOf("M01", "M02", "M03", "M04")

    private fun hostIsAnchor() = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = "M01",
            anchorId = "M01",
            members = members4
        )
    )

    private fun hostNotAnchor() = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = "M01",
            anchorId = "M04",
            members = members4
        )
    )

    @Test
    fun admissionTtl_onlyInvitePending() {
        assertTrue(
            ConferenceAcceptedMediaHandoffContract.mayAdmissionTtlAbort(
                ConferenceAcceptedMediaHandoffPhase.INVITE_PENDING
            )
        )
        listOf(
            ConferenceAcceptedMediaHandoffPhase.ACCEPTED_WAIT_MEDIA,
            ConferenceAcceptedMediaHandoffPhase.REALIZATION_RUNNING,
            ConferenceAcceptedMediaHandoffPhase.REALIZED,
            ConferenceAcceptedMediaHandoffPhase.MEDIA_FAILED
        ).forEach {
            assertFalse(ConferenceAcceptedMediaHandoffContract.mayAdmissionTtlAbort(it))
        }
    }

    @Test
    fun pendingMap_mutexAfterAcceptedSession() {
        assertTrue(
            ConferenceAcceptedMediaHandoffContract.mayArmPendingInvite("s1", acceptedConferenceSessionId = null)
        )
        assertFalse(
            ConferenceAcceptedMediaHandoffContract.mayArmPendingInvite("s1", acceptedConferenceSessionId = "s1")
        )
        assertTrue(
            ConferenceAcceptedMediaHandoffContract.mayArmPendingInvite("s2", acceptedConferenceSessionId = "s1")
        )
    }

    @Test
    fun blankAccept_isWaitMedia_notMediaReject() {
        assertTrue(ConferenceAcceptedMediaHandoffContract.blankAcceptIsWaitMedia(sdpBlank = true))
        assertFalse(ConferenceAcceptedMediaHandoffContract.blankAcceptIsWaitMedia(sdpBlank = false))
    }

    @Test
    fun spokeDoesNotStartRealization_anchorDoes() {
        val snap = hostIsAnchor()
        assertTrue(
            ConferenceAcceptedMediaHandoffContract.mayStartRealizationAfterMembershipAccept("M01", snap)
        )
        assertFalse(
            ConferenceAcceptedMediaHandoffContract.mayStartRealizationAfterMembershipAccept("M03", snap)
        )
        val hostNotAnchor = hostNotAnchor()
        assertFalse(
            ConferenceAcceptedMediaHandoffContract.mayStartRealizationAfterMembershipAccept("M01", hostNotAnchor)
        )
        assertTrue(
            ConferenceAcceptedMediaHandoffContract.mayStartRealizationAfterMembershipAccept("M04", hostNotAnchor)
        )
    }

    @Test
    fun meshSpoke_doesNotGainRealizationOwner() {
        val mesh = ConferenceTopologySnapshot(
            conferenceId = "c1",
            rosterEpoch = 1L,
            anchorEpoch = 0L,
            anchorId = null,
            meshGeneration = 0L,
            topologyMode = ConferenceTopologyMode.MESH,
            hostModuleId = "M01",
            members = listOf("M01", "M02", "M03"),
            actualMediaEdges = emptySet()
        )
        assertFalse(
            ConferenceAcceptedMediaHandoffContract.mayStartRealizationAfterMembershipAccept("M01", mesh)
        )
        assertFalse(
            ConferenceAcceptedMediaHandoffContract.mayStartRealizationAfterMembershipAccept("M03", mesh)
        )
    }

    @Test
    fun duplicateInviteAfterAccept_ignored() {
        assertTrue(
            ConferenceAcceptedMediaHandoffContract.duplicateInviteAfterAccept(
                existingSessionId = "s1",
                incomingSessionId = "s1",
                existingAccepted = true
            )
        )
        assertFalse(
            ConferenceAcceptedMediaHandoffContract.duplicateInviteAfterAccept(
                existingSessionId = "s1",
                incomingSessionId = "s1",
                existingAccepted = false
            )
        )
    }
}
