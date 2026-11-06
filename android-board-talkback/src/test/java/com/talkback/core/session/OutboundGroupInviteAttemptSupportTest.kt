package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundGroupInviteAttemptSupportTest {

    private fun session(): TalkbackSession =
        TalkbackSession(
            id = "grp:CH-01",
            type = SessionType.GROUP,
            local = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            channelId = "CH-01"
        )

    private fun attempt(
        semantic: GroupInvitePayloadSemantic,
        handoffSucceeded: Boolean = true,
        terminalReason: String? = null
    ) = OutboundGroupInviteAttempt(
        offerLineageId = "GM1",
        deliveryAttemptId = 1L,
        sessionId = "grp:CH-01",
        remoteModuleId = "M03",
        semantic = semantic,
        issuedAtMs = 1000L,
        handoffSucceeded = handoffSucceeded,
        terminalReason = terminalReason
    )

    @Test
    fun recordSuccessfulHandoff_bootstrapSemantic_isActiveAndInFlight() {
        val session = session()
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = "M03",
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.BOOTSTRAP_SDP_INVITE,
            offerLineageId = "GM1",
            deliveryAttemptId = 1L,
            issuedAtMs = 1000L
        )
        val active = OutboundGroupInviteAttemptSupport.activeAttempt(session, "M03")
        assertEquals("GM1", active?.offerLineageId)
        assertTrue(OutboundGroupInviteAttemptSupport.isRemoteSignalingInFlight(session, "M03"))
    }

    @Test
    fun blockedHandoff_noAttemptRecorded_notInFlight() {
        val session = session()
        assertNull(OutboundGroupInviteAttemptSupport.activeAttempt(session, "M03"))
        assertFalse(OutboundGroupInviteAttemptSupport.isRemoteSignalingInFlight(session, "M03"))
    }

    @Test
    fun terminalAttempt_notInFlight() {
        val session = session()
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = "M03",
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE,
            offerLineageId = "GM2",
            deliveryAttemptId = 1L
        )
        OutboundGroupInviteAttemptSupport.markTerminal(session, "M03", "MESH_LINK_COMPLETED")
        assertFalse(OutboundGroupInviteAttemptSupport.isRemoteSignalingInFlight(session, "M03"))
        assertNull(OutboundGroupInviteAttemptSupport.activeAttempt(session, "M03"))
    }

    @Test
    fun snapshotSemantic_neverInFlight_evenIfRecorded() {
        val session = session()
        session.outboundGroupInviteAttemptsByRemoteModule["M03"] =
            attempt(semantic = GroupInvitePayloadSemantic.MEMBERSHIP_SNAPSHOT_ONLY)
        assertFalse(OutboundGroupInviteAttemptSupport.isRemoteSignalingInFlight(session, "M03"))
        assertFalse(OutboundGroupInviteAttemptSupport.isActive(session.outboundGroupInviteAttemptsByRemoteModule["M03"]))
    }

    @Test
    fun handoffNotSucceeded_notInFlight() {
        val session = session()
        session.outboundGroupInviteAttemptsByRemoteModule["M03"] =
            attempt(
                semantic = GroupInvitePayloadSemantic.BOOTSTRAP_SDP_INVITE,
                handoffSucceeded = false
            )
        assertFalse(OutboundGroupInviteAttemptSupport.isRemoteSignalingInFlight(session, "M03"))
    }

    @Test
    fun staleAttempt_expires_soPlannerMayReinvite() {
        val session = session()
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = "M03",
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.BOOTSTRAP_SDP_INVITE,
            offerLineageId = "GM1",
            deliveryAttemptId = 1L,
            issuedAtMs = 1000L
        )
        val staleNowMs = 1000L + OutboundGroupInviteAttemptSupport.ATTEMPT_STALE_TIMEOUT_MS
        assertEquals(
            OutboundGroupInviteAttemptSupport.ATTEMPT_STALE_TIMEOUT_MS,
            OutboundGroupInviteAttemptSupport.expireStaleAttempt(session, "M03", staleNowMs)
        )
        assertFalse(OutboundGroupInviteAttemptSupport.isRemoteSignalingInFlight(session, "M03"))
        assertEquals(
            OutboundGroupInviteAttemptSupport.TERMINAL_REASON_TIMEOUT,
            session.outboundGroupInviteAttemptsByRemoteModule["M03"]?.terminalReason
        )
    }

    @Test
    fun freshAttempt_doesNotExpire() {
        val session = session()
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = "M03",
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE,
            offerLineageId = "GM2",
            deliveryAttemptId = 1L,
            issuedAtMs = 1000L
        )
        assertNull(OutboundGroupInviteAttemptSupport.expireStaleAttempt(session, "M03", 1500L))
        assertTrue(OutboundGroupInviteAttemptSupport.isRemoteSignalingInFlight(session, "M03"))
    }

    @Test
    fun markDeliverySatisfiedFromGroupAccept_closesActiveAttempt() {
        val session = session()
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = "M03",
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.BOOTSTRAP_SDP_INVITE,
            offerLineageId = "GM1",
            deliveryAttemptId = 1L,
        )
        OutboundGroupInviteAttemptSupport.markDeliverySatisfiedFromGroupAccept(session, "M03")
        assertTrue(OutboundGroupInviteAttemptSupport.isDeliverySatisfied(session, "M03"))
        assertFalse(OutboundGroupInviteAttemptSupport.isRemoteSignalingInFlight(session, "M03"))
    }

    @Test
    fun controllerOwnsNegotiation_doesNotExpireCheckingAttempt() {
        val session = session()
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = "M03",
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE,
            offerLineageId = "GM2",
            deliveryAttemptId = 1L,
            issuedAtMs = 1000L,
        )
        val lateMs = 1000L + OutboundGroupInviteAttemptSupport.ATTEMPT_STALE_TIMEOUT_MS + 1L
        assertNull(
            OutboundGroupInviteAttemptSupport.expireStaleAttempt(
                session = session,
                remoteModuleId = "M03",
                nowMs = lateMs,
                iceState = "CHECKING",
                meshLinkCompleted = false,
                controllerOwnsNegotiation = true,
            )
        )
        assertTrue(OutboundGroupInviteAttemptSupport.isRemoteSignalingInFlight(session, "M03"))
    }

    @Test
    fun staleAttempt_expiresEarlyWhenIceCheckingAndMeshIncomplete() {
        val session = session()
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = "M03",
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE,
            offerLineageId = "GM2",
            deliveryAttemptId = 1L,
            issuedAtMs = 1000L,
        )
        val earlyMs = 1000L + GroupMeshReconciler.CHECKING_STUCK_MS
        assertEquals(
            GroupMeshReconciler.CHECKING_STUCK_MS,
            OutboundGroupInviteAttemptSupport.expireStaleAttempt(
                session = session,
                remoteModuleId = "M03",
                nowMs = earlyMs,
                iceState = "CHECKING",
                meshLinkCompleted = false,
            )
        )
        assertFalse(OutboundGroupInviteAttemptSupport.isRemoteSignalingInFlight(session, "M03"))
    }

    @Test
    fun shouldIceRestartAfterTimeoutWithEngine() {
        val session = session()
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = "M04",
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE,
            offerLineageId = "GM1",
            deliveryAttemptId = 1L,
            issuedAtMs = 1000L,
        )
        OutboundGroupInviteAttemptSupport.expireStaleAttempt(
            session = session,
            remoteModuleId = "M04",
            nowMs = 1000L + OutboundGroupInviteAttemptSupport.ATTEMPT_STALE_TIMEOUT_MS,
        )
        assertTrue(
            OutboundGroupInviteAttemptSupport.shouldIceRestartForPairwiseOffer(
                session = session,
                remoteModuleId = "M04",
                iceState = "CHECKING",
                hasMeshEngine = true,
            )
        )
        assertFalse(
            OutboundGroupInviteAttemptSupport.shouldIceRestartForPairwiseOffer(
                session = session,
                remoteModuleId = "M04",
                iceState = null,
                hasMeshEngine = false,
            )
        )
    }

    @Test
    fun firstOffer_noAttemptNoIceSnapshot_doesNotIceRestart() {
        // 11-06 run 2: bootstrap offer in flight, GIDR retry 450 ms later must not change ufrag.
        val session = session()
        assertFalse(
            OutboundGroupInviteAttemptSupport.shouldIceRestartForPairwiseOffer(
                session = session,
                remoteModuleId = "M04",
                iceState = null,
                hasMeshEngine = true,
            )
        )
        assertTrue(
            OutboundGroupInviteAttemptSupport.shouldIceRestartForPairwiseOffer(
                session = session,
                remoteModuleId = "M04",
                iceState = "CHECKING",
                hasMeshEngine = true,
            )
        )
    }

    @Test
    fun localOfferAlone_doesNotAffectSupportEvaluation() {
        val session = session()
        assertFalse(OutboundGroupInviteAttemptSupport.isRemoteSignalingInFlight(session, "M03"))
    }
}
