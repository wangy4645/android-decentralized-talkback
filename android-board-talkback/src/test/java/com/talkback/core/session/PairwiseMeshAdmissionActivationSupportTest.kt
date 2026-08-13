package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairwiseMeshAdmissionActivationSupportTest {

    private fun obligation(
        offerer: String,
        answerer: String,
        satisfied: Boolean
    ): PairwiseMeshAdmissionObligation {
        val edge = PairwiseMeshEdgeKey.of(ModuleId(offerer), ModuleId(answerer))
        return PairwiseMeshAdmissionObligation(
            edge = edge,
            offerer = ModuleId(offerer),
            answerer = ModuleId(answerer),
            signalingSatisfied = satisfied
        )
    }

    private fun input(
        localId: String,
        obligation: PairwiseMeshAdmissionObligation?,
        peerEdgeReady: Boolean = true,
        sessionValid: Boolean = true,
        signalingInFlight: Boolean = false,
        iceConnected: Boolean = false,
        remoteDiscovered: Boolean = true,
        cooldownElapsed: Boolean = true
    ) = PairwiseMeshAdmissionActivationSupport.ActivationEvaluationInput(
        localModuleId = ModuleId(localId),
        obligation = obligation,
        peerEdgeReady = peerEdgeReady,
        sessionValid = sessionValid,
        signalingInFlight = signalingInFlight,
        iceConnected = iceConnected,
        remoteDiscovered = remoteDiscovered,
        cooldownElapsed = cooldownElapsed
    )

    @Test
    fun evaluate_offererWithUnsatisfiedEdge_issuesInvite() {
        val decision = PairwiseMeshAdmissionActivationSupport.evaluate(
            input(
                localId = "M01",
                obligation = obligation("M01", "M03", satisfied = false)
            )
        )
        assertEquals(
            PairwiseMeshAdmissionActivationSupport.ActivationDecision.IssueInvite("M03"),
            decision
        )
    }

    @Test
    fun evaluate_answererDefersWithoutOffer() {
        val decision = PairwiseMeshAdmissionActivationSupport.evaluate(
            input(
                localId = "M03",
                obligation = obligation("M01", "M03", satisfied = false)
            )
        )
        assertEquals(
            PairwiseMeshAdmissionActivationSupport.ActivationDecision.Deferred("ANSWERER_AWAITING_OFFER"),
            decision
        )
    }

    @Test
    fun evaluate_satisfiedObligation_noAction() {
        val decision = PairwiseMeshAdmissionActivationSupport.evaluate(
            input(
                localId = "M01",
                obligation = obligation("M01", "M03", satisfied = true)
            )
        )
        assertEquals(PairwiseMeshAdmissionActivationSupport.ActivationDecision.NoAction, decision)
    }

    @Test
    fun evaluate_signalingInFlight_deferred() {
        val decision = PairwiseMeshAdmissionActivationSupport.evaluate(
            input(
                localId = "M01",
                obligation = obligation("M01", "M03", satisfied = false),
                signalingInFlight = true
            )
        )
        assertEquals(
            PairwiseMeshAdmissionActivationSupport.ActivationDecision.Deferred("SIGNALING_IN_FLIGHT"),
            decision
        )
    }

    @Test
    fun shouldUsePairwiseAdmissionInvite_trueForLocalOffererOnly() {
        val offererSession = TalkbackSession(
            id = "grp:CH",
            type = SessionType.GROUP,
            local = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            channelId = "CH"
        ).apply {
            accepted = true
            GroupMembershipSupport.applyGroupMembersList(
                this,
                listOf("M01", "M02", "M03").map { EndpointAddress(ModuleId(it), EndpointId("E01")) }
            )
        }
        val answererSession = TalkbackSession(
            id = "grp:CH",
            type = SessionType.GROUP,
            local = EndpointAddress(ModuleId("M03"), EndpointId("E01")),
            channelId = "CH"
        ).apply {
            accepted = true
            GroupMembershipSupport.applyGroupMembersList(
                this,
                listOf("M01", "M02", "M03").map { EndpointAddress(ModuleId(it), EndpointId("E01")) }
            )
        }
        assertTrue(
            PairwiseMeshAdmissionActivationSupport.shouldUsePairwiseAdmissionInvite(
                offererSession,
                ModuleId("M03")
            )
        )
        assertTrue(
            PairwiseMeshAdmissionActivationSupport.isAnswererAwaitingPairwiseOffer(
                answererSession,
                ModuleId("M01")
            )
        )
    }
}
