package com.talkback.core.session



import com.talkback.core.model.EndpointAddress

import com.talkback.core.model.EndpointId

import com.talkback.core.model.ModuleId

import org.junit.Assert.assertEquals

import org.junit.Test



/** ADR-0064 Patch A.1 — satisfied validity re-evaluation fixtures. */

class Adr0064PatchA1GidrScopeTest {



    private fun session(remoteModuleId: String = "M02"): TalkbackSession =

        TalkbackSession(

            id = "grp:CH-01",

            type = SessionType.GROUP,

            local = EndpointAddress(ModuleId("M01"), EndpointId("E01")),

            channelId = "CH-01",

        ).apply {

            pendingInviteeEndpoints[remoteModuleId] =

                EndpointAddress(ModuleId(remoteModuleId), EndpointId("E02"))

        }



    private fun recordHandoff(session: TalkbackSession, remoteModuleId: String) {

        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(

            session = session,

            remoteModuleId = remoteModuleId,

            sessionId = session.id,

            semantic = GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE,

            offerLineageId = "GM6",

            deliveryAttemptId = 1L,

        )

    }



    private fun markScopedSatisfied(

        session: TalkbackSession,

        remoteModuleId: String,

        offerLineageId: String,

        pcGeneration: Long,

    ) {

        OutboundGroupInviteAttemptSupport.markDeliveryCompleted(

            session = session,

            remoteModuleId = remoteModuleId,

            terminalReason = OutboundGroupInviteAttemptSupport.TERMINAL_REASON_GROUP_ACCEPT,

            completedBindingOfferLineageId = offerLineageId,

            completedBindingPcGeneration = pcGeneration,

        )

    }



    private fun recordLiveBinding(

        session: TalkbackSession,

        remoteModuleId: String,

        offerLineageId: String,

        pcGeneration: Long,

    ) {

        GroupPcLineageBindingSupport.recordOffererBinding(

            session = session,

            remoteModuleId = remoteModuleId,

            offerLineageId = offerLineageId,

            pcLineage = pcGeneration,

        )

    }



    private fun phaseAndGate(

        session: TalkbackSession,

        remoteModuleId: String,

        pcGeneration: Long,

        edgeObservation: GroupInviteDeliverySatisfiedScopeSupport.EdgeObservation? = null,

    ): Pair<GroupInviteDeliveryRecoverySupport.DeliveryPhase, GroupInviteDeliveryRecoverySupport.ReconciliationGate> {

        val phase =

            GroupInviteDeliveryRecoverySupport.deliveryPhase(

                session,

                remoteModuleId,

                5000L,

                currentPcGeneration = pcGeneration,

                edgeObservation = edgeObservation,

            )

        val gate =

            GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(

                session,

                remoteModuleId,

                5000L,

                currentPcGeneration = pcGeneration,

                edgeObservation = edgeObservation,

            )

        return phase to gate

    }



    /** Case A — fenced binding / no live authoritative binding. */

    @Test

    fun caseA_fencedBinding_noLiveBinding_permitRetry() {

        val session = session()

        recordHandoff(session, "M02")

        markScopedSatisfied(session, "M02", offerLineageId = "GM6", pcGeneration = 3L)

        recordLiveBinding(session, "M02", offerLineageId = "GM6", pcGeneration = 3L)

        GroupPcLineageBindingSupport.binding(session, "M02", "GM6")!!.let {

            GroupPcLineageBindingSupport.fenceBinding(session, it)

        }

        assertEquals(

            GroupInviteDeliverySatisfiedScopeSupport.SatisfiedValidity.STALE_BINDING,

            GroupInviteDeliverySatisfiedScopeSupport.evaluateSatisfiedValidity(

                attempt = session.outboundGroupInviteAttemptsByRemoteModule["M02"],

                session = session,

                remoteModuleId = "M02",

                currentPcGeneration = 3L,

            ),

        )

        val (phase, gate) = phaseAndGate(session, "M02", pcGeneration = 3L)

        assertEquals(GroupInviteDeliveryRecoverySupport.DeliveryPhase.INVALIDATED_RETRY_ELIGIBLE, phase)

        assertEquals(GroupInviteDeliveryRecoverySupport.ReconciliationGate.PermitRetry, gate)

    }



    /** Case A variant — strong edge invalid (FAILED) with matching live binding. */

    @Test

    fun caseA_failedIce_matchingBinding_permitRetry() {

        val session = session()

        recordHandoff(session, "M02")

        markScopedSatisfied(session, "M02", offerLineageId = "GM6", pcGeneration = 3L)

        recordLiveBinding(session, "M02", offerLineageId = "GM6", pcGeneration = 3L)

        val edge =

            GroupInviteDeliverySatisfiedScopeSupport.EdgeObservation(

                iceState = "FAILED",

                bindingState = GroupOfferBinding.BindingState.LIVE,

            )

        assertEquals(

            GroupInviteDeliverySatisfiedScopeSupport.SatisfiedValidity.STALE_EDGE,

            GroupInviteDeliverySatisfiedScopeSupport.evaluateSatisfiedValidity(

                attempt = session.outboundGroupInviteAttemptsByRemoteModule["M02"],

                session = session,

                remoteModuleId = "M02",

                currentPcGeneration = 3L,

                edgeObservation = edge,

            ),

        )

        val (phase, gate) = phaseAndGate(session, "M02", pcGeneration = 3L, edgeObservation = edge)

        assertEquals(GroupInviteDeliveryRecoverySupport.DeliveryPhase.INVALIDATED_RETRY_ELIGIBLE, phase)

        assertEquals(GroupInviteDeliveryRecoverySupport.ReconciliationGate.PermitRetry, gate)

    }



    /** Case B — matching binding + ICE CONNECTED still suppresses. */

    @Test

    fun caseB_matchingBinding_connectedIce_stillSuppresses() {

        val session = session()

        recordHandoff(session, "M02")

        markScopedSatisfied(session, "M02", offerLineageId = "GM6", pcGeneration = 3L)

        recordLiveBinding(session, "M02", offerLineageId = "GM6", pcGeneration = 3L)

        val edge =

            GroupInviteDeliverySatisfiedScopeSupport.EdgeObservation(

                iceState = "CONNECTED",

                bindingState = GroupOfferBinding.BindingState.LIVE,

            )

        assertEquals(

            GroupInviteDeliverySatisfiedScopeSupport.SatisfiedValidity.VALID,

            GroupInviteDeliverySatisfiedScopeSupport.evaluateSatisfiedValidity(

                attempt = session.outboundGroupInviteAttemptsByRemoteModule["M02"],

                session = session,

                remoteModuleId = "M02",

                currentPcGeneration = 3L,

                edgeObservation = edge,

            ),

        )

        val (phase, gate) = phaseAndGate(session, "M02", pcGeneration = 3L, edgeObservation = edge)

        assertEquals(GroupInviteDeliveryRecoverySupport.DeliveryPhase.SATISFIED, phase)

        assertEquals(GroupInviteDeliveryRecoverySupport.ReconciliationGate.SuppressSatisfied, gate)

    }



    /** Case C — CHECKING must not reopen (anti-storm). */

    @Test

    fun caseC_matchingBinding_checkingIce_stillSuppresses() {

        val session = session()

        recordHandoff(session, "M02")

        markScopedSatisfied(session, "M02", offerLineageId = "GM6", pcGeneration = 3L)

        recordLiveBinding(session, "M02", offerLineageId = "GM6", pcGeneration = 3L)

        val edge =

            GroupInviteDeliverySatisfiedScopeSupport.EdgeObservation(

                iceState = "CHECKING",

                bindingState = GroupOfferBinding.BindingState.LIVE,

            )

        assertEquals(

            GroupInviteDeliverySatisfiedScopeSupport.SatisfiedValidity.VALID,

            GroupInviteDeliverySatisfiedScopeSupport.evaluateSatisfiedValidity(

                attempt = session.outboundGroupInviteAttemptsByRemoteModule["M02"],

                session = session,

                remoteModuleId = "M02",

                currentPcGeneration = 3L,

                edgeObservation = edge,

            ),

        )

        val (phase, gate) = phaseAndGate(session, "M02", pcGeneration = 3L, edgeObservation = edge)

        assertEquals(GroupInviteDeliveryRecoverySupport.DeliveryPhase.SATISFIED, phase)

        assertEquals(GroupInviteDeliveryRecoverySupport.ReconciliationGate.SuppressSatisfied, gate)

    }



    /** Case D — pcGeneration bump invalidates satisfied scope. */

    @Test

    fun caseD_generationBump_staleBinding_permitRetry() {

        val session = session()

        recordHandoff(session, "M02")

        markScopedSatisfied(session, "M02", offerLineageId = "GM6", pcGeneration = 3L)

        recordLiveBinding(session, "M02", offerLineageId = "GM7", pcGeneration = 4L)

        assertEquals(

            GroupInviteDeliverySatisfiedScopeSupport.SatisfiedValidity.STALE_BINDING,

            GroupInviteDeliverySatisfiedScopeSupport.evaluateSatisfiedValidity(

                attempt = session.outboundGroupInviteAttemptsByRemoteModule["M02"],

                session = session,

                remoteModuleId = "M02",

                currentPcGeneration = 4L,

            ),

        )

        val (phase, gate) = phaseAndGate(session, "M02", pcGeneration = 4L)

        assertEquals(GroupInviteDeliveryRecoverySupport.DeliveryPhase.INVALIDATED_RETRY_ELIGIBLE, phase)

        assertEquals(GroupInviteDeliveryRecoverySupport.ReconciliationGate.PermitRetry, gate)

    }



    /**

     * Case E — field-equivalent stuck shape: CHECKING + meshMissingIce diagnostic must not invalidate.

     */

    @Test

    fun caseE_checkingWithMeshMissingIceDiagnostic_stillSuppresses() {

        val session = session(remoteModuleId = "M04")

        recordHandoff(session, "M04")

        markScopedSatisfied(session, "M04", offerLineageId = "GM4", pcGeneration = 2L)

        recordLiveBinding(session, "M04", offerLineageId = "GM4", pcGeneration = 2L)

        val edge =

            GroupInviteDeliverySatisfiedScopeSupport.EdgeObservation(

                iceState = "CHECKING",

                bindingState = GroupOfferBinding.BindingState.LIVE,

                meshMissingIce = true,

            )

        assertEquals(

            GroupInviteDeliverySatisfiedScopeSupport.SatisfiedValidity.VALID,

            GroupInviteDeliverySatisfiedScopeSupport.evaluateSatisfiedValidity(

                attempt = session.outboundGroupInviteAttemptsByRemoteModule["M04"],

                session = session,

                remoteModuleId = "M04",

                currentPcGeneration = 2L,

                edgeObservation = edge,

            ),

        )

        val (phase, gate) = phaseAndGate(session, "M04", pcGeneration = 2L, edgeObservation = edge)

        assertEquals(GroupInviteDeliveryRecoverySupport.DeliveryPhase.SATISFIED, phase)

        assertEquals(GroupInviteDeliveryRecoverySupport.ReconciliationGate.SuppressSatisfied, gate)

    }

}


