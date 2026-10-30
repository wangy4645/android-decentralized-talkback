package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceGroupAcceptNegotiationContractTest {

    private val members4 = listOf("M01", "M02", "M03", "M04")

    private fun hostIsAnchor() = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = "M01",
            anchorId = "M01",
            members = members4
        )
    )

    private fun hostIsAnchorWithoutM04Edge(): ConferenceTopologySnapshot {
        val full = hostIsAnchor()
        val edgesWithoutM04 = full.actualMediaEdges.filter { it.remoteModuleId != "M04" }.toSet()
        return full.copy(actualMediaEdges = edgesWithoutM04)
    }

    @Test
    fun hostPendingCr_blankAccept_deferred_notMembershipTerminal() {
        assertTrue(
            ConferenceGroupAcceptNegotiationContract.shouldDeferBlankHostGroupAccept(
                usesHostRealizationAccept = true,
                hostOutstandingOfferLineageId = "CR37",
            )
        )
        assertFalse(
            ConferenceGroupAcceptNegotiationContract.mayApplyMembershipOnlyBlankConferenceAccept(
                usesHostRealizationAccept = true,
                hostOutstandingOfferLineageId = "CR37",
            )
        )
    }

    @Test
    fun hostNoPendingOffer_blankAccept_mayUseMembershipOnlyPath() {
        assertFalse(
            ConferenceGroupAcceptNegotiationContract.shouldDeferBlankHostGroupAccept(
                usesHostRealizationAccept = true,
                hostOutstandingOfferLineageId = null,
            )
        )
        assertTrue(
            ConferenceGroupAcceptNegotiationContract.mayApplyMembershipOnlyBlankConferenceAccept(
                usesHostRealizationAccept = true,
                hostOutstandingOfferLineageId = null,
            )
        )
    }

    @Test
    fun participant_notHost_blankSdp_neverAnswers() {
        assertFalse(
            ConferenceGroupAcceptNegotiationContract.mayAnswerInboundHostConferenceOffer(
                existingSessionId = "s1",
                inviteSessionId = "s1",
                localModuleId = "M04",
                hostCallerModuleId = "M01",
                sessionInitiatorModuleId = "M01",
                payloadSdpBlank = true,
                snapshot = hostIsAnchor(),
            )
        )
    }

    @Test
    fun anchorSecondSpoke_hostOfferWithSdp_mayAnswerEvenIfEdgeNotYetInSnapshot() {
        val snap = hostIsAnchorWithoutM04Edge()
        assertFalse(
            ConferenceMediaEdgeRealizationContract.shouldAcceptAnchorOfferOnExistingSession(
                existingSessionId = "s1",
                inviteSessionId = "s1",
                localModuleId = "M04",
                callerModuleId = "M01",
                payloadSdpBlank = false,
                snapshot = snap,
            )
        )
        assertTrue(
            ConferenceGroupAcceptNegotiationContract.mayAnswerInboundHostConferenceOffer(
                existingSessionId = "s1",
                inviteSessionId = "s1",
                localModuleId = "M04",
                hostCallerModuleId = "M01",
                sessionInitiatorModuleId = "M01",
                payloadSdpBlank = false,
                snapshot = snap,
            )
        )
    }

    @Test
    fun anchorSpoke_withAdmittedEdge_existingContractStillPasses() {
        val snap = hostIsAnchor()
        assertTrue(
            ConferenceGroupAcceptNegotiationContract.mayAnswerInboundHostConferenceOffer(
                existingSessionId = "s1",
                inviteSessionId = "s1",
                localModuleId = "M04",
                hostCallerModuleId = "M01",
                sessionInitiatorModuleId = "M01",
                payloadSdpBlank = false,
                snapshot = snap,
            )
        )
    }

    @Test
    fun rejoin_preservedAnchor_hostInviteWithSdp_shouldBeginMediaAnswer_withoutAuthoritySnapshot() {
        assertTrue(
            ConferenceGroupAcceptNegotiationContract.shouldBeginInboundHostConferenceMediaAnswer(
                existingSessionId = "s1",
                inviteSessionId = "s1",
                localModuleId = "M04",
                hostCallerModuleId = "M01",
                sessionInitiatorModuleId = "M01",
                payloadSdpBlank = false,
                snapshot = null,
                declaredMode = ConferenceTopologyMode.ANCHOR,
                sessionAnchorModuleId = "M01",
            )
        )
    }

    @Test
    fun rejoin_preservedAnchor_blankSdp_doesNotBeginMediaAnswer() {
        assertFalse(
            ConferenceGroupAcceptNegotiationContract.shouldBeginInboundHostConferenceMediaAnswer(
                existingSessionId = "s1",
                inviteSessionId = "s1",
                localModuleId = "M04",
                hostCallerModuleId = "M01",
                sessionInitiatorModuleId = "M01",
                payloadSdpBlank = true,
                snapshot = null,
                declaredMode = ConferenceTopologyMode.ANCHOR,
                sessionAnchorModuleId = "M01",
            )
        )
    }

    @Test
    fun hostInvite_wrongSession_rejected() {
        assertFalse(
            ConferenceGroupAcceptNegotiationContract.shouldBeginInboundHostConferenceMediaAnswer(
                existingSessionId = "s1",
                inviteSessionId = "s2",
                localModuleId = "M04",
                hostCallerModuleId = "M01",
                sessionInitiatorModuleId = "M01",
                payloadSdpBlank = false,
                snapshot = null,
                declaredMode = ConferenceTopologyMode.ANCHOR,
                sessionAnchorModuleId = "M01",
            )
        )
    }

    @Test
    fun hostInvite_nonInitiatorHost_rejected() {
        assertFalse(
            ConferenceGroupAcceptNegotiationContract.shouldBeginInboundHostConferenceMediaAnswer(
                existingSessionId = "s1",
                inviteSessionId = "s1",
                localModuleId = "M04",
                hostCallerModuleId = "M02",
                sessionInitiatorModuleId = "M01",
                payloadSdpBlank = false,
                snapshot = null,
                declaredMode = ConferenceTopologyMode.ANCHOR,
                sessionAnchorModuleId = "M01",
            )
        )
    }

    @Test
    fun staleBlank_deferDoesNotDependOnParticipantPath() {
        assertTrue(
            ConferenceGroupAcceptNegotiationContract.shouldDeferBlankHostGroupAccept(
                usesHostRealizationAccept = true,
                hostOutstandingOfferLineageId = "CR37",
            )
        )
        assertFalse(
            ConferenceGroupAcceptNegotiationContract.shouldDeferBlankHostGroupAccept(
                usesHostRealizationAccept = false,
                hostOutstandingOfferLineageId = "CR37",
            )
        )
    }
}
