package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0064 Patch A — SI-2 scoped satisfied suppression fixtures. */
class Adr0064PatchAGidrScopeTest {

    private fun session(): TalkbackSession =
        TalkbackSession(
            id = "grp:CH-01",
            type = SessionType.GROUP,
            local = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            channelId = "CH-01",
        ).apply {
            pendingInviteeEndpoints["M02"] =
                EndpointAddress(ModuleId("M02"), EndpointId("E02"))
        }

    private fun recordHandoff(session: TalkbackSession) {
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = "M02",
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE,
            offerLineageId = "GM6",
            deliveryAttemptId = 1L,
        )
    }

    private fun markScopedSatisfied(
        session: TalkbackSession,
        offerLineageId: String,
        pcGeneration: Long,
    ) {
        OutboundGroupInviteAttemptSupport.markDeliveryCompleted(
            session = session,
            remoteModuleId = "M02",
            terminalReason = OutboundGroupInviteAttemptSupport.TERMINAL_REASON_GROUP_ACCEPT,
            completedBindingOfferLineageId = offerLineageId,
            completedBindingPcGeneration = pcGeneration,
        )
    }

    @Test
    fun siEg1_pcGenerationBump_permitRetryDespiteHistoricalTerminal() {
        val session = session()
        recordHandoff(session)
        markScopedSatisfied(session, offerLineageId = "GM6", pcGeneration = 3L)
        GroupPcLineageBindingSupport.recordOffererBinding(
            session = session,
            remoteModuleId = "M02",
            offerLineageId = "GM7",
            pcLineage = 4L,
        )
        assertEquals(
            GroupInviteDeliveryRecoverySupport.DeliveryPhase.INVALIDATED_RETRY_ELIGIBLE,
            GroupInviteDeliveryRecoverySupport.deliveryPhase(session, "M02", 5000L, currentPcGeneration = 4L),
        )
        assertEquals(
            GroupInviteDeliveryRecoverySupport.ReconciliationGate.PermitRetry,
            GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(
                session,
                "M02",
                5000L,
                currentPcGeneration = 4L,
            ),
        )
        assertTrue(
            OutboundGroupInviteAttemptSupport.hasDeliverySatisfiedTerminal(
                session.outboundGroupInviteAttemptsByRemoteModule["M02"],
            ),
        )
    }

    @Test
    fun siEg2_matchingIncarnation_stillSuppressesRetry() {
        val session = session()
        recordHandoff(session)
        markScopedSatisfied(session, offerLineageId = "GM6", pcGeneration = 3L)
        GroupPcLineageBindingSupport.recordOffererBinding(
            session = session,
            remoteModuleId = "M02",
            offerLineageId = "GM6",
            pcLineage = 3L,
        )
        assertEquals(
            GroupInviteDeliveryRecoverySupport.DeliveryPhase.SATISFIED,
            GroupInviteDeliveryRecoverySupport.deliveryPhase(session, "M02", 5000L, currentPcGeneration = 3L),
        )
        assertEquals(
            GroupInviteDeliveryRecoverySupport.ReconciliationGate.SuppressSatisfied,
            GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(
                session,
                "M02",
                5000L,
                currentPcGeneration = 3L,
            ),
        )
    }

    @Test
    fun m01M02ReplayClass_staleCompletedBindingOnNewGeneration() {
        val session = session()
        recordHandoff(session)
        markScopedSatisfied(session, offerLineageId = "GM6", pcGeneration = 3L)
        assertFalse(
            GroupInviteDeliverySatisfiedScopeSupport.suppressesDeliveryRetry(
                attempt = session.outboundGroupInviteAttemptsByRemoteModule["M02"],
                session = session,
                remoteModuleId = "M02",
                currentPcGeneration = 4L,
            ),
        )
    }

    @Test
    fun legacyTerminalWithoutOverlay_doesNotSuppress() {
        val session = session()
        recordHandoff(session)
        OutboundGroupInviteAttemptSupport.markTerminal(session, "M02", "MESH_LINK_COMPLETED")
        assertEquals(
            GroupInviteDeliveryRecoverySupport.DeliveryPhase.INVALIDATED_RETRY_ELIGIBLE,
            GroupInviteDeliveryRecoverySupport.deliveryPhase(session, "M02", 5000L, currentPcGeneration = 3L),
        )
    }
}
