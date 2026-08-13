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
    fun localOfferAlone_doesNotAffectSupportEvaluation() {
        val session = session()
        assertFalse(OutboundGroupInviteAttemptSupport.isRemoteSignalingInFlight(session, "M03"))
    }
}
