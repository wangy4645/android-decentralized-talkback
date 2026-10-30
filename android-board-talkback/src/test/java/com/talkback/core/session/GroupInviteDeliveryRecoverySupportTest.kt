package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInviteDeliveryRecoverySupportTest {

    private fun session(): TalkbackSession =
        TalkbackSession(
            id = "grp:CH-01",
            type = SessionType.GROUP,
            local = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            channelId = "CH-01",
        ).apply {
            pendingInviteeEndpoints["M03"] =
                EndpointAddress(ModuleId("M03"), EndpointId("E03"))
        }

    @Test
    fun noAttempt_permitRetry() {
        val session = session()
        assertEquals(
            GroupInviteDeliveryRecoverySupport.ReconciliationGate.PermitRetry,
            GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(session, "M03", 1000L),
        )
    }

    @Test
    fun activeAttempt_suppressActive() {
        val session = session()
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = "M03",
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE,
            offerLineageId = "GM1",
            deliveryAttemptId = 1L,
            issuedAtMs = 1000L,
        )
        assertEquals(
            GroupInviteDeliveryRecoverySupport.DeliveryPhase.ACTIVE,
            GroupInviteDeliveryRecoverySupport.deliveryPhase(session, "M03", 1500L),
        )
        assertEquals(
            GroupInviteDeliveryRecoverySupport.ReconciliationGate.SuppressActive,
            GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(session, "M03", 1500L),
        )
        assertFalse(
            GroupInviteDeliveryRecoverySupport.isDeliveryRetryEligible(session, "M03", 1500L),
        )
    }

    @Test
    fun staleAttempt_permitRetry() {
        val session = session()
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = "M03",
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.BOOTSTRAP_SDP_INVITE,
            offerLineageId = "GM1",
            deliveryAttemptId = 1L,
            issuedAtMs = 1000L,
        )
        val staleAt = 1000L + OutboundGroupInviteAttemptSupport.ATTEMPT_STALE_TIMEOUT_MS + 1L
        assertEquals(
            GroupInviteDeliveryRecoverySupport.DeliveryPhase.STALE_RETRY_ELIGIBLE,
            GroupInviteDeliveryRecoverySupport.deliveryPhase(session, "M03", staleAt),
        )
        assertEquals(
            GroupInviteDeliveryRecoverySupport.ReconciliationGate.PermitRetry,
            GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(session, "M03", staleAt),
        )
        assertTrue(
            GroupInviteDeliveryRecoverySupport.isDeliveryRetryEligible(session, "M03", staleAt),
        )
    }

    @Test
    fun groupAcceptMarked_satisfied() {
        val session = session()
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = "M03",
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE,
            offerLineageId = "GM1",
            deliveryAttemptId = 1L,
        )
        OutboundGroupInviteAttemptSupport.markDeliverySatisfiedFromGroupAccept(session, "M03")
        assertEquals(
            GroupInviteDeliveryRecoverySupport.DeliveryPhase.SATISFIED,
            GroupInviteDeliveryRecoverySupport.deliveryPhase(session, "M03", 5000L),
        )
        assertEquals(
            GroupInviteDeliveryRecoverySupport.ReconciliationGate.SuppressSatisfied,
            GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(session, "M03", 5000L),
        )
    }
}
