package com.talkback.core.session



import com.talkback.core.model.EndpointAddress

import com.talkback.core.model.EndpointId

import com.talkback.core.model.ModuleId

import org.junit.Assert.assertEquals

import org.junit.Assert.assertFalse

import org.junit.Assert.assertTrue

import org.junit.Test



/** ADR-0065 Patch-2B — observation router + reconcile merge fixtures. */

class Adr0065Patch2BObservationRouterTest {



    private fun endpoint(moduleId: String) =

        EndpointAddress(ModuleId(moduleId), EndpointId("E01"))



    private fun groupSession(localId: String, memberIds: List<String>): TalkbackSession {

        val session = TalkbackSession(

            id = "grp:CH-01",

            type = SessionType.GROUP,

            local = endpoint(localId),

            channelId = "CH-01",

        )

        session.accepted = true

        GroupMembershipSupport.applyGroupMembersList(session, memberIds.map { endpoint(it) })

        PairwiseMeshAdmissionSupport.reconcile(session)

        return session

    }



    private fun route(

        session: TalkbackSession,

        remoteModuleId: String,

        observation: PairwiseMeshObligationObservation,

        currentPcGeneration: Long? = null,

    ): PairwiseMeshObligationObservationRouter.RouteResult =

        PairwiseMeshAdmissionSupport.applyObservation(

            session = session,

            remoteModuleId = ModuleId(remoteModuleId),

            observation = observation,

            currentPcGeneration = currentPcGeneration,

        )



    private fun lifecycle(session: TalkbackSession, remoteModuleId: String): PairwiseMeshObligationState? =

        PairwiseMeshAdmissionSupport.obligationForRemotePeer(session, ModuleId(remoteModuleId))?.lifecycle



    /** P2-B1 — delivery accept observation reaches MEDIA_PENDING, not SATISFIED. */

    @Test

    fun p2B1_deliveryGroupAcceptReceived_mediaPendingNotSatisfied() {

        val session = groupSession(localId = "M02", memberIds = listOf("M02", "M04"))

        route(session, "M04", PairwiseMeshObligationDeliveryBoundary.inviteDispatched())

        val result = route(session, "M04", PairwiseMeshObligationDeliveryBoundary.deliveryAccepted())

        assertTrue(result.applied)

        assertEquals(PairwiseMeshObligationState.MEDIA_PENDING, lifecycle(session, "M04"))

        assertFalse(result.nextLifecycle == PairwiseMeshObligationState.SATISFIED)

    }



    /** P2-B2 — ICE connected with binding prerequisites reaches SATISFIED. */

    @Test

    fun p2B2_iceConnectedWithValidBinding_reachesSatisfied() {

        val session = groupSession(localId = "M02", memberIds = listOf("M02", "M04"))

        route(session, "M04", PairwiseMeshObligationDeliveryBoundary.deliveryAccepted())

        val binding =

            GroupPcLineageBindingSupport.recordOffererBinding(

                session = session,

                remoteModuleId = "M04",

                offerLineageId = "OL-1",

                pcLineage = 3L,

            )

        GroupPcLineageBindingSupport.markSignalingAccepted(session, binding)

        val result =

            route(

                session = session,

                remoteModuleId = "M04",

                observation =

                    PairwiseMeshObligationIceBoundary.connected(

                        session = session,

                        remoteModuleId = "M04",

                        currentPcGeneration = 3L,

                    ),

                currentPcGeneration = 3L,

            )

        assertTrue(result.applied)

        assertEquals(PairwiseMeshObligationState.SATISFIED, lifecycle(session, "M04"))

    }



    /** P2-B3 — reconcile merge preserves router lifecycle. */

    @Test

    fun p2B3_reconcileMerge_preservesMediaPendingAfterMeshCompletedCompat() {

        val session = groupSession(localId = "M02", memberIds = listOf("M02", "M04"))

        route(session, "M04", PairwiseMeshObligationDeliveryBoundary.deliveryAccepted())

        session.meshCompletedModules.add("M04")

        PairwiseMeshAdmissionSupport.reconcile(session)

        assertEquals(PairwiseMeshObligationState.MEDIA_PENDING, lifecycle(session, "M04"))

        assertTrue(

            PairwiseMeshAdmissionSupport.obligationForRemotePeer(session, ModuleId("M04"))!!

                .signalingSatisfied

        )

    }



    /** P2-B4 — meshMissingIce diagnostic does not transition lifecycle; queues reevaluation. */

    @Test

    fun p2B4_meshMissingIceDiagnostic_noLifecycleTransition_enqueuesReevaluation() {

        val session = groupSession(localId = "M02", memberIds = listOf("M02", "M04"))

        route(session, "M04", PairwiseMeshObligationDeliveryBoundary.deliveryAccepted())

        val edgeKey = PairwiseMeshEdgeKey.of(session.local.moduleId, ModuleId("M04")).storageKey()

        val result =

            route(

                session,

                "M04",

                PairwiseMeshObligationHealthBoundary.meshMissingIceDiagnostic(meshMissingIce = true),

                currentPcGeneration = 1L,

            )

        assertTrue(result.applied)

        assertEquals(PairwiseMeshObligationState.MEDIA_PENDING, lifecycle(session, "M04"))

        assertTrue(session.pendingPairwiseObligationReevaluationQueue.any { it.edgeKey == edgeKey })

    }



    /** P2-B5 — duplicate observation dropped by dedupe. */

    @Test

    fun p2B5_duplicateObservation_dedupeDropped() {

        val session = groupSession(localId = "M02", memberIds = listOf("M02", "M04"))

        val observation = PairwiseMeshObligationDeliveryBoundary.deliveryAccepted()

        val first = route(session, "M04", observation)

        val second = route(session, "M04", observation)

        assertTrue(first.applied)

        assertFalse(second.applied)

        assertTrue(second.dedupeDropped)

        assertEquals(PairwiseMeshObligationState.MEDIA_PENDING, lifecycle(session, "M04"))

    }



    /**

     * P2-B6 — stale observation ordering: late gen-3 CONNECT after gen-4 fence

     * MUST NOT resurrect SATISFIED.

     */

    @Test

    fun p2B6_staleObservationOrdering_doesNotResurrectSatisfied() {

        val session = groupSession(localId = "M02", memberIds = listOf("M02", "M04"))

        route(session, "M04", PairwiseMeshObligationDeliveryBoundary.deliveryAccepted())

        val gen3Binding =

            GroupPcLineageBindingSupport.recordOffererBinding(

                session = session,

                remoteModuleId = "M04",

                offerLineageId = "OL-G3",

                pcLineage = 3L,

            )

        GroupPcLineageBindingSupport.markSignalingAccepted(session, gen3Binding)

        route(

            session = session,

            remoteModuleId = "M04",

            observation =

                PairwiseMeshObligationIceBoundary.connected(

                    session = session,

                    remoteModuleId = "M04",

                    currentPcGeneration = 3L,

                ),

            currentPcGeneration = 3L,

        )

        assertEquals(PairwiseMeshObligationState.SATISFIED, lifecycle(session, "M04"))



        val gen4Binding =

            GroupPcLineageBindingSupport.recordOffererBinding(

                session = session,

                remoteModuleId = "M04",

                offerLineageId = "OL-G4",

                pcLineage = 4L,

            )

        route(

            session = session,

            remoteModuleId = "M04",

            observation = PairwiseMeshObligationGplbBoundary.bindingIncarnationInvalid(currentPcGeneration = 4L),

            currentPcGeneration = 4L,

        )

        assertEquals(PairwiseMeshObligationState.ACTIVATION_REQUIRED, lifecycle(session, "M04"))



        val stale =

            route(

                session = session,

                remoteModuleId = "M04",

                observation =

                    PairwiseMeshObligationObservation.MediaIceConnected(

                        pcGeneration = 3L,

                        signalingCompleted = true,

                        bindingValid = true,

                        bindingFenced = false,

                    ),

                currentPcGeneration = 4L,

            )

        assertTrue(stale.staleDropped)

        assertEquals(PairwiseMeshObligationState.ACTIVATION_REQUIRED, lifecycle(session, "M04"))

        GroupPcLineageBindingSupport.markSignalingAccepted(session, gen4Binding)

    }

}


