package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupBootstrapAdmissionSupportTest {

    @Test
    fun create_intentStartsPending() {
        val intent = GroupBootstrapAdmissionSupport.create(
            channelId = "CH-01",
            targetModuleId = "M02",
            createReason = "DISCOVERED_NO_SESSION",
            nowMs = 1000L
        )
        assertEquals(BootstrapAdmissionIntentState.PENDING, intent.state)
        assertEquals(BootstrapAdmissionRequiredAction.GROUP_INVITE, intent.requiredAction)
        assertEquals("CH-01|M02", intent.key.storageKey())
    }

    @Test
    fun markWaiting_transitionsToWaitingEdgeReady() {
        val pending = GroupBootstrapAdmissionSupport.create("CH-01", "M02", "DISCOVERED_NO_SESSION")
        val waiting = GroupBootstrapAdmissionSupport.markWaiting(pending, "LOCAL_NOT_BIDIRECTIONAL", 2000L)
        assertEquals(BootstrapAdmissionIntentState.WAITING_EDGE_READY, waiting.state)
        assertEquals("LOCAL_NOT_BIDIRECTIONAL", waiting.waitingReason)
        assertEquals(2000L, waiting.updatedAtMs)
    }

    @Test
    fun markInviteSent_recordsSessionId() {
        val pending = GroupBootstrapAdmissionSupport.create("CH-01", "M02", "DISCOVERED_NO_SESSION")
        val sent = GroupBootstrapAdmissionSupport.markInviteSent(pending, "grp:CH-01", 3000L)
        assertEquals(BootstrapAdmissionIntentState.INVITE_SENT, sent.state)
        assertEquals("grp:CH-01", sent.sessionId)
        assertEquals(null, sent.waitingReason)
    }

    @Test
    fun isBootstrapAdmissionPeer_trueWhenPendingNotCanonical() {
        val session = TalkbackSession(
            id = "grp:CH-01",
            local = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            type = SessionType.GROUP,
            channelId = "CH-01"
        ).apply {
            pendingInviteeEndpoints["M02"] = EndpointAddress(ModuleId("M02"), EndpointId("E02"))
        }
        assertTrue(GroupBootstrapAdmissionSupport.isBootstrapAdmissionPeer(session, "M02"))
    }

    @Test
    fun isBootstrapAdmissionPeer_falseWhenCanonical() {
        val m02 = EndpointAddress(ModuleId("M02"), EndpointId("E02"))
        val session = TalkbackSession(
            id = "grp:CH-01",
            local = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            type = SessionType.GROUP,
            channelId = "CH-01"
        ).apply {
            groupMembers = listOf(m02)
            pendingInviteeEndpoints["M02"] = m02
        }
        assertFalse(GroupBootstrapAdmissionSupport.isBootstrapAdmissionPeer(session, "M02"))
    }
}
