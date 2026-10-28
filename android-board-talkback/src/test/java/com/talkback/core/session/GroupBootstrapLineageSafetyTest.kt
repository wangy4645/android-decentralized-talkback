package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GROUP bootstrap establishment-window lineage safety (Seam A/B). */
class GroupBootstrapLineageSafetyTest {

    private fun session(remoteModuleId: String = "M03"): TalkbackSession =
        TalkbackSession(
            id = "grp:CH-01",
            type = SessionType.GROUP,
            local = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            channelId = "CH-01",
        ).apply {
            pendingInviteeEndpoints[remoteModuleId] =
                EndpointAddress(ModuleId(remoteModuleId), EndpointId("E02"))
        }

    private fun recordHandoff(
        session: TalkbackSession,
        remoteModuleId: String,
        offerLineageId: String,
        establishmentSessionLineageId: String?,
    ) {
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = remoteModuleId,
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.BOOTSTRAP_SDP_INVITE,
            offerLineageId = offerLineageId,
            deliveryAttemptId = 1L,
            establishmentSessionLineageId = establishmentSessionLineageId,
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

    @Test
    fun oldLineageAccept_doesNotSatisfyNewEstablishmentWindow() {
        val remote = "M03"
        val s = session(remote)
        recordHandoff(s, remote, offerLineageId = "GM11", establishmentSessionLineageId = "window-a")
        recordLiveBinding(s, remote, offerLineageId = "GM11", pcGeneration = 8L)

        val marked =
            OutboundGroupInviteAttemptSupport.markDeliverySatisfiedFromGroupAccept(
                session = s,
                remoteModuleId = remote,
                completedBindingOfferLineageId = "GM11",
                completedBindingPcGeneration = 8L,
                currentEstablishmentSessionLineageId = "window-b",
            )

        assertFalse(marked)
        assertFalse(OutboundGroupInviteAttemptSupport.isDeliverySatisfied(s, remote))
    }

    @Test
    fun currentWindowAccept_satisfiesWhenBindingMatches() {
        val remote = "M03"
        val s = session(remote)
        recordHandoff(s, remote, offerLineageId = "GM12", establishmentSessionLineageId = "window-b")
        recordLiveBinding(s, remote, offerLineageId = "GM12", pcGeneration = 8L)

        val marked =
            OutboundGroupInviteAttemptSupport.markDeliverySatisfiedFromGroupAccept(
                session = s,
                remoteModuleId = remote,
                completedBindingOfferLineageId = "GM12",
                completedBindingPcGeneration = 8L,
                currentEstablishmentSessionLineageId = "window-b",
            )

        assertTrue(marked)
        assertTrue(OutboundGroupInviteAttemptSupport.isDeliverySatisfied(s, remote))
    }

    @Test
    fun currentWindowSatisfied_suppressesRetry() {
        val remote = "M03"
        val s = session(remote)
        recordHandoff(s, remote, offerLineageId = "GM12", establishmentSessionLineageId = "window-b")
        recordLiveBinding(s, remote, offerLineageId = "GM12", pcGeneration = 8L)
        OutboundGroupInviteAttemptSupport.markDeliveryCompleted(
            session = s,
            remoteModuleId = remote,
            terminalReason = OutboundGroupInviteAttemptSupport.TERMINAL_REASON_GROUP_ACCEPT,
            completedBindingOfferLineageId = "GM12",
            completedBindingPcGeneration = 8L,
        )

        val gate =
            GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(
                session = s,
                remoteModuleId = remote,
                currentPcGeneration = 8L,
                currentEstablishmentSessionLineageId = "window-b",
            )
        assertEquals(GroupInviteDeliveryRecoverySupport.ReconciliationGate.SuppressSatisfied, gate)
    }

    @Test
    fun windowChange_invalidatesDeliveryProofWithoutFencingGplb() {
        val remote = "M03"
        val s = session(remote)
        recordHandoff(s, remote, offerLineageId = "GM11", establishmentSessionLineageId = "window-a")
        recordLiveBinding(s, remote, offerLineageId = "GM11", pcGeneration = 8L)
        OutboundGroupInviteAttemptSupport.markDeliveryCompleted(
            session = s,
            remoteModuleId = remote,
            terminalReason = OutboundGroupInviteAttemptSupport.TERMINAL_REASON_GROUP_ACCEPT,
            completedBindingOfferLineageId = "GM11",
            completedBindingPcGeneration = 8L,
        )

        OutboundGroupInviteAttemptSupport.invalidateDeliveryProofForEstablishmentWindow(s, "window-b")

        val attempt = s.outboundGroupInviteAttemptsByRemoteModule[remote]!!
        assertEquals(DeliverySatisfiedStatus.INVALIDATED, attempt.satisfiedStatus)
        val liveBinding =
            GroupPcLineageBindingSupport.liveBindingForPcLineage(
                session = s,
                remoteModuleId = remote,
                pcLineage = 8L,
            )
        assertEquals(GroupOfferBinding.BindingState.LIVE, liveBinding?.state)

        val gate =
            GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(
                session = s,
                remoteModuleId = remote,
                currentPcGeneration = 8L,
                currentEstablishmentSessionLineageId = "window-b",
            )
        assertEquals(GroupInviteDeliveryRecoverySupport.ReconciliationGate.PermitRetry, gate)
    }

    @Test
    fun staleActiveAttempt_blocksDispatchForCurrentWindow() {
        val remote = "M03"
        val s = session(remote)
        recordHandoff(s, remote, offerLineageId = "GM11", establishmentSessionLineageId = "window-a")

        assertFalse(
            GroupInviteDeliveryEstablishmentWindowSupport.admissionRelevantDispatchAllowed(
                attempt = s.outboundGroupInviteAttemptsByRemoteModule[remote],
                currentEstablishmentSessionLineageId = "window-b",
            )
        )
    }
}
