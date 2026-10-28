package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0065 Patch-2C — P2C-I2a incarnation scope fixtures. */
class Adr0065Patch2CP2cI2aIncarnationScopeTest {

    private fun session(localId: String = "M02", remoteId: String = "M04"): TalkbackSession =
        TalkbackSession(
            id = "grp:CH-01",
            type = SessionType.GROUP,
            local = EndpointAddress(ModuleId(localId), EndpointId("E01")),
            channelId = "CH-01",
        ).apply {
            accepted = true
            GroupMembershipSupport.applyGroupMembersList(
                this,
                listOf(localId, remoteId).map { EndpointAddress(ModuleId(it), EndpointId("E01")) },
            )
            PairwiseMeshAdmissionSupport.reconcile(this)
        }

    private fun markScopedSatisfied(
        session: TalkbackSession,
        remoteModuleId: String,
        offerLineageId: String,
        pcGeneration: Long,
    ) {
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = remoteModuleId,
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE,
            offerLineageId = offerLineageId,
            deliveryAttemptId = 1L,
        )
        OutboundGroupInviteAttemptSupport.markDeliveryCompleted(
            session = session,
            remoteModuleId = remoteModuleId,
            terminalReason = OutboundGroupInviteAttemptSupport.TERMINAL_REASON_GROUP_ACCEPT,
            completedBindingOfferLineageId = offerLineageId,
            completedBindingPcGeneration = pcGeneration,
        )
    }

    /** I2a-1 — gen3 delivery cannot lift suppress for gen4 authoritative target. */
    @Test
    fun i2a1_completedGen3AuthoritativeGen4_c3G2LiftDoesNotApply() {
        val session = session()
        markScopedSatisfied(session, "M04", "OL-G3", pcGeneration = 3L)
        GroupPcLineageBindingSupport.recordOffererBinding(
            session = session,
            remoteModuleId = "M04",
            offerLineageId = "OL-G4",
            pcLineage = 4L,
        )
        val scope =
            PairwiseMeshObligationEstablishmentScopeSupport.Scope(
                edgeKey = PairwiseMeshEdgeKey.of(ModuleId("M02"), ModuleId("M04")),
                lifecycle = PairwiseMeshObligationState.ACTIVATION_REQUIRED,
                evaluationPcGeneration = 4L,
            )
        val validity =
            GroupInviteDeliverySatisfiedScopeSupport.evaluateSatisfiedValidity(
                attempt = session.outboundGroupInviteAttemptsByRemoteModule["M04"],
                session = session,
                remoteModuleId = "M04",
                currentPcGeneration = 4L,
                obligationEstablishmentScope = scope,
            )
        assertEquals(GroupInviteDeliverySatisfiedScopeSupport.SatisfiedValidity.STALE_BINDING, validity)
    }

    /** I2a-2 — same incarnation ACTIVATION_REQUIRED lifts suppress via C3-G2. */
    @Test
    fun i2a2_sameIncarnationActivationRequired_permitRetryViaPhaseChain() {
        val session = session()
        markScopedSatisfied(session, "M04", "OL-G4", pcGeneration = 4L)
        GroupPcLineageBindingSupport.recordOffererBinding(
            session = session,
            remoteModuleId = "M04",
            offerLineageId = "OL-G4",
            pcLineage = 4L,
        )
        val edgeKey = PairwiseMeshEdgeKey.of(ModuleId("M02"), ModuleId("M04")).storageKey()
        session.pairwiseMeshAdmissionObligations[edgeKey] =
            session.pairwiseMeshAdmissionObligations.getValue(edgeKey).copy(
                lifecycle = PairwiseMeshObligationState.ACTIVATION_REQUIRED,
            )
        val scope =
            PairwiseMeshObligationEstablishmentScopeSupport.establishmentScope(
                session = session,
                remoteModuleId = "M04",
                currentPcGeneration = 4L,
            )!!
        val validity =
            GroupInviteDeliverySatisfiedScopeSupport.evaluateSatisfiedValidity(
                attempt = session.outboundGroupInviteAttemptsByRemoteModule["M04"],
                session = session,
                remoteModuleId = "M04",
                currentPcGeneration = 4L,
                edgeObservation =
                    GroupInviteDeliverySatisfiedScopeSupport.EdgeObservation(
                        iceState = "CHECKING",
                    ),
                obligationEstablishmentScope = scope,
            )
        assertNotEquals(GroupInviteDeliverySatisfiedScopeSupport.SatisfiedValidity.VALID, validity)
        assertEquals(
            GroupInviteDeliveryRecoverySupport.DeliveryPhase.INVALIDATED_RETRY_ELIGIBLE,
            GroupInviteDeliveryRecoverySupport.deliveryPhase(
                session = session,
                remoteModuleId = "M04",
                currentPcGeneration = 4L,
                edgeObservation =
                    GroupInviteDeliverySatisfiedScopeSupport.EdgeObservation(
                        iceState = "CHECKING",
                    ),
                obligationEstablishmentScope = scope,
            ),
        )
        assertEquals(
            GroupInviteDeliveryRecoverySupport.ReconciliationGate.PermitRetry,
            GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(
                session = session,
                remoteModuleId = "M04",
                currentPcGeneration = 4L,
                edgeObservation =
                    GroupInviteDeliverySatisfiedScopeSupport.EdgeObservation(
                        iceState = "CHECKING",
                    ),
                obligationEstablishmentScope = scope,
            ),
        )
    }

    /** I2a-3 — PermitRetry path with stale activation incarnation must defer. */
    @Test
    fun i2a3_permitRetryWithStaleActivationIncarnation_deferred() {
        val session = session()
        val edgeKey = PairwiseMeshEdgeKey.of(ModuleId("M02"), ModuleId("M04")).storageKey()
        session.pairwiseMeshAdmissionObligations[edgeKey] =
            session.pairwiseMeshAdmissionObligations.getValue(edgeKey).copy(
                lifecycle = PairwiseMeshObligationState.ACTIVATION_REQUIRED,
            )
        GroupPcLineageBindingSupport.recordOffererBinding(
            session = session,
            remoteModuleId = "M04",
            offerLineageId = "OL-G4",
            pcLineage = 4L,
        )
        val decision =
            PairwiseMeshAdmissionActivationSupport.evaluate(
                PairwiseMeshAdmissionActivationSupport.ActivationEvaluationInput(
                    session = session,
                    localModuleId = ModuleId("M02"),
                    obligation = session.pairwiseMeshAdmissionObligations[edgeKey],
                    peerEdgeReady = true,
                    sessionValid = true,
                    signalingInFlight = false,
                    iceConnected = false,
                    remoteDiscovered = true,
                    cooldownElapsed = true,
                    currentPcGeneration = 99L,
                ),
            )
        assertEquals(
            PairwiseMeshAdmissionActivationSupport.ActivationDecision.Deferred("STALE_OBLIGATION_INCARNATION"),
            decision,
        )
    }

    /** I2a-4 — SuppressActive wins over obligation reopening. */
    @Test
    fun i2a4_suppressActiveBlocksDespiteActivationRequired() {
        val session = session()
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = "M04",
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE,
            offerLineageId = "OL-ACTIVE",
            deliveryAttemptId = 2L,
        )
        val edgeKey = PairwiseMeshEdgeKey.of(ModuleId("M02"), ModuleId("M04")).storageKey()
        session.pairwiseMeshAdmissionObligations[edgeKey] =
            session.pairwiseMeshAdmissionObligations.getValue(edgeKey).copy(
                lifecycle = PairwiseMeshObligationState.ACTIVATION_REQUIRED,
            )
        val scope =
            PairwiseMeshObligationEstablishmentScopeSupport.establishmentScope(
                session = session,
                remoteModuleId = "M04",
                currentPcGeneration = 1L,
            )!!
        assertEquals(
            GroupInviteDeliveryRecoverySupport.ReconciliationGate.SuppressActive,
            GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(
                session = session,
                remoteModuleId = "M04",
                currentPcGeneration = 1L,
                obligationEstablishmentScope = scope,
            ),
        )
    }
}
