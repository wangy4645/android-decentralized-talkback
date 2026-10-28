package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0065 Patch-2C — activation authority contract fixtures P2C-A1..A8. */
class Adr0065Patch2CActivationAuthorityTest {

    private val drainConfig =
        PairwiseMeshObligationReducer.Config(
            checkingGraceMs = 10_000L,
        )

    private fun groupSession(localId: String, memberIds: List<String>): TalkbackSession {
        val session =
            TalkbackSession(
                id = "grp:CH-01",
                type = SessionType.GROUP,
                local = EndpointAddress(ModuleId(localId), EndpointId("E01")),
                channelId = "CH-01",
            )
        session.accepted = true
        GroupMembershipSupport.applyGroupMembersList(
            session,
            memberIds.map { EndpointAddress(ModuleId(it), EndpointId("E01")) },
        )
        PairwiseMeshAdmissionSupport.reconcile(session)
        return session
    }

    private fun markDeliverySatisfied(
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

    private fun setLifecycle(
        session: TalkbackSession,
        remoteModuleId: String,
        lifecycle: PairwiseMeshObligationState,
    ) {
        val edgeKey = PairwiseMeshEdgeKey.of(session.local.moduleId, ModuleId(remoteModuleId)).storageKey()
        session.pairwiseMeshAdmissionObligations[edgeKey] =
            session.pairwiseMeshAdmissionObligations.getValue(edgeKey).copy(lifecycle = lifecycle)
    }

    /** P2C-A1 — within grace: MEDIA_PENDING, GIDR may suppress, Activation NoAction. */
    @Test
    fun a1_mediaPendingWithinGrace_gidrMaySuppress_activationNoAction() {
        val session = groupSession("M02", listOf("M02", "M04"))
        markDeliverySatisfied(session, "M04", "OL-1", pcGeneration = 4L)
        GroupPcLineageBindingSupport.recordOffererBinding(
            session = session,
            remoteModuleId = "M04",
            offerLineageId = "OL-1",
            pcLineage = 4L,
        )
        setLifecycle(session, "M04", PairwiseMeshObligationState.MEDIA_PENDING)
        session.meshCompletedModules.add("M04")
        val scope =
            PairwiseMeshObligationEstablishmentScopeSupport.establishmentScope(
                session,
                "M04",
                4L,
            )!!
        val edgeObs =
            GroupInviteDeliverySatisfiedScopeSupport.EdgeObservation(iceState = "CHECKING")
        assertEquals(
            GroupInviteDeliverySatisfiedScopeSupport.SatisfiedValidity.VALID,
            GroupInviteDeliverySatisfiedScopeSupport.evaluateSatisfiedValidity(
                session.outboundGroupInviteAttemptsByRemoteModule["M04"],
                session,
                "M04",
                4L,
                edgeObs,
                scope,
            ),
        )
        val activation =
            PairwiseMeshAdmissionActivationSupport.evaluate(
                PairwiseMeshAdmissionActivationSupport.ActivationEvaluationInput(
                    session = session,
                    localModuleId = ModuleId("M02"),
                    obligation =
                        PairwiseMeshAdmissionSupport.obligationForRemotePeer(session, ModuleId("M04")),
                    peerEdgeReady = true,
                    sessionValid = true,
                    signalingInFlight = false,
                    iceConnected = false,
                    remoteDiscovered = true,
                    cooldownElapsed = true,
                    currentPcGeneration = 4L,
                ),
            )
        assertEquals(PairwiseMeshAdmissionActivationSupport.ActivationDecision.NoAction, activation)
    }

    /** P2C-A2 — drain grace exceeded → ACTIVATION_REQUIRED. */
    @Test
    fun a2_drainGraceExceeded_activationRequired() {
        val session = groupSession("M02", listOf("M02", "M04"))
        setLifecycle(session, "M04", PairwiseMeshObligationState.MEDIA_PENDING)
        session.pendingPairwiseObligationReevaluationQueue.add(
            ReevaluationQueueItem(
                sessionId = session.id,
                edgeKey = PairwiseMeshEdgeKey.of(ModuleId("M02"), ModuleId("M04")).storageKey(),
                pcGeneration = 4L,
                enqueuedAtMs = 0L,
            ),
        )
        val result =
            PairwiseMeshObligationReevaluationDrain.drainOne(
                session = session,
                input =
                    PairwiseMeshObligationReevaluationDrain.DrainInput(
                        membershipConvergenceAllowed = true,
                        currentPcGenerationForRemote = { 4L },
                        iceSnapshotForRemote = {
                            PairwiseMeshObligationReevaluationDrain.IceSnapshot(
                                iceState = "CHECKING",
                                elapsedCheckingMs = 15_000L,
                            )
                        },
                        config = drainConfig,
                    ),
            )
        assertTrue(result.consumed)
        assertTrue(result.routed)
        assertEquals(
            PairwiseMeshObligationState.ACTIVATION_REQUIRED,
            PairwiseMeshAdmissionSupport.obligationForRemotePeer(session, ModuleId("M04"))!!.lifecycle,
        )
        assertTrue(session.pendingPairwiseObligationReevaluationQueue.isEmpty())
    }

    /** P2C-A3 — C3-G2 validity path → PermitRetry without bypass. */
    @Test
    fun a3_c3G2ValidityChain_permitRetryNotBypass() {
        val session = groupSession("M02", listOf("M02", "M04"))
        markDeliverySatisfied(session, "M04", "OL-1", pcGeneration = 4L)
        GroupPcLineageBindingSupport.recordOffererBinding(
            session = session,
            remoteModuleId = "M04",
            offerLineageId = "OL-1",
            pcLineage = 4L,
        )
        setLifecycle(session, "M04", PairwiseMeshObligationState.ACTIVATION_REQUIRED)
        val scope =
            PairwiseMeshObligationEstablishmentScopeSupport.establishmentScope(session, "M04", 4L)!!
        assertEquals(
            GroupInviteDeliveryRecoverySupport.ReconciliationGate.PermitRetry,
            GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(
                session = session,
                remoteModuleId = "M04",
                currentPcGeneration = 4L,
                edgeObservation =
                    GroupInviteDeliverySatisfiedScopeSupport.EdgeObservation(iceState = "CHECKING"),
                obligationEstablishmentScope = scope,
            ),
        )
    }

    /** P2C-A6 — compat signalingSatisfied ignored when lifecycle MEDIA_PENDING. */
    @Test
    fun a6_compatSignalingSatisfiedIgnored() {
        val session = groupSession("M02", listOf("M02", "M04"))
        session.meshCompletedModules.add("M04")
        PairwiseMeshAdmissionSupport.reconcile(session)
        setLifecycle(session, "M04", PairwiseMeshObligationState.MEDIA_PENDING)
        val ob = PairwiseMeshAdmissionSupport.obligationForRemotePeer(session, ModuleId("M04"))!!
        assertTrue(ob.signalingSatisfied)
        val decision =
            PairwiseMeshAdmissionActivationSupport.evaluate(
                PairwiseMeshAdmissionActivationSupport.ActivationEvaluationInput(
                    session = session,
                    localModuleId = ModuleId("M02"),
                    obligation = ob,
                    peerEdgeReady = true,
                    sessionValid = true,
                    signalingInFlight = false,
                    iceConnected = false,
                    remoteDiscovered = true,
                    cooldownElapsed = true,
                    currentPcGeneration = 4L,
                ),
            )
        assertEquals(PairwiseMeshAdmissionActivationSupport.ActivationDecision.NoAction, decision)
    }

    /**
     * P2C-A7 — 17:02 deadlock shape must have legal exit:
     * ACTIVATION_REQUIRED + same incarnation historical delivery → PermitRetry + IssueInvite eligible.
     */
    @Test
    fun a7_1702DeadlockShape_reachesPermitRetryAndEligibleIssueInvite() {
        val session = groupSession("M02", listOf("M02", "M04"))
        markDeliverySatisfied(session, "M04", "OL-1", pcGeneration = 4L)
        GroupPcLineageBindingSupport.recordOffererBinding(
            session = session,
            remoteModuleId = "M04",
            offerLineageId = "OL-1",
            pcLineage = 4L,
        )
        GroupPcLineageBindingSupport.markSignalingAccepted(
            session,
            GroupPcLineageBindingSupport.liveBindingForPcLineage(session, "M04", 4L)!!,
        )
        setLifecycle(session, "M04", PairwiseMeshObligationState.ACTIVATION_REQUIRED)
        val scope =
            PairwiseMeshObligationEstablishmentScopeSupport.establishmentScope(session, "M04", 4L)!!
        val attempt = session.outboundGroupInviteAttemptsByRemoteModule["M04"]
        val edgeObs =
            GroupInviteDeliverySatisfiedScopeSupport.EdgeObservation(iceState = "CHECKING")
        assertNotEquals(
            GroupInviteDeliverySatisfiedScopeSupport.SatisfiedValidity.VALID,
            GroupInviteDeliverySatisfiedScopeSupport.evaluateSatisfiedValidity(
                attempt,
                session,
                "M04",
                4L,
                edgeObs,
                scope,
            ),
        )
        assertEquals(
            GroupInviteDeliveryRecoverySupport.DeliveryPhase.INVALIDATED_RETRY_ELIGIBLE,
            GroupInviteDeliveryRecoverySupport.deliveryPhase(
                session,
                "M04",
                currentPcGeneration = 4L,
                edgeObservation = edgeObs,
                obligationEstablishmentScope = scope,
            ),
        )
        assertEquals(
            GroupInviteDeliveryRecoverySupport.ReconciliationGate.PermitRetry,
            GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(
                session,
                "M04",
                currentPcGeneration = 4L,
                edgeObservation = edgeObs,
                obligationEstablishmentScope = scope,
            ),
        )
        val activation =
            PairwiseMeshAdmissionActivationSupport.evaluate(
                PairwiseMeshAdmissionActivationSupport.ActivationEvaluationInput(
                    session = session,
                    localModuleId = ModuleId("M02"),
                    obligation =
                        PairwiseMeshAdmissionSupport.obligationForRemotePeer(session, ModuleId("M04")),
                    peerEdgeReady = true,
                    sessionValid = true,
                    signalingInFlight = false,
                    iceConnected = false,
                    remoteDiscovered = true,
                    cooldownElapsed = true,
                    currentPcGeneration = 4L,
                ),
            )
        assertEquals(
            PairwiseMeshAdmissionActivationSupport.ActivationDecision.IssueInvite("M04"),
            activation,
        )
    }

    /** P2C-A7 anti — SuppressActive blocks dispatch eligibility. */
    @Test
    fun a7Anti_suppressActive_noDispatchEligibility() {
        val session = groupSession("M02", listOf("M02", "M04"))
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = "M04",
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE,
            offerLineageId = "OL-ACTIVE",
            deliveryAttemptId = 3L,
        )
        setLifecycle(session, "M04", PairwiseMeshObligationState.ACTIVATION_REQUIRED)
        val scope =
            PairwiseMeshObligationEstablishmentScopeSupport.establishmentScope(session, "M04", 1L)!!
        assertEquals(
            GroupInviteDeliveryRecoverySupport.ReconciliationGate.SuppressActive,
            GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(
                session,
                "M04",
                currentPcGeneration = 1L,
                obligationEstablishmentScope = scope,
            ),
        )
    }

    /**
     * P2C-A8 — queue(gen3) + current gen4 → consume/drop, no observation routed, no replacement item.
     */
    @Test
    fun a8_drainDropsStalePcGeneration_noRouterObservation() {
        val session = groupSession("M02", listOf("M02", "M04"))
        setLifecycle(session, "M04", PairwiseMeshObligationState.MEDIA_PENDING)
        session.pendingPairwiseObligationReevaluationQueue.add(
            ReevaluationQueueItem(
                sessionId = session.id,
                edgeKey = PairwiseMeshEdgeKey.of(ModuleId("M02"), ModuleId("M04")).storageKey(),
                pcGeneration = 3L,
                enqueuedAtMs = 0L,
            ),
        )
        val before = session.pairwiseMeshAdmissionObligations.values.first().lifecycle
        val result =
            PairwiseMeshObligationReevaluationDrain.drainOne(
                session = session,
                input =
                    PairwiseMeshObligationReevaluationDrain.DrainInput(
                        membershipConvergenceAllowed = true,
                        currentPcGenerationForRemote = { 4L },
                        iceSnapshotForRemote = {
                            PairwiseMeshObligationReevaluationDrain.IceSnapshot(
                                iceState = "CHECKING",
                                elapsedCheckingMs = 15_000L,
                            )
                        },
                    ),
            )
        assertTrue(result.consumed)
        assertTrue(result.dropped)
        assertFalse(result.routed)
        assertEquals("STALE_INCARNATION", result.reason)
        assertEquals(before, session.pairwiseMeshAdmissionObligations.values.first().lifecycle)
        assertTrue(session.pendingPairwiseObligationReevaluationQueue.isEmpty())
    }
}
