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
    fun evaluateEdgeReadyRetry_issuesInvite_whenEligible() {
        val intent = GroupBootstrapAdmissionSupport.markWaiting(
            GroupBootstrapAdmissionSupport.create("CH-01", "M02", "DISCOVERED_NO_SESSION"),
            "WAITING_EDGE_NOT_READY:LOCAL_NOT_BIDIRECTIONAL"
        )
        val endpoint = EndpointAddress(ModuleId("M02"), EndpointId("E02"))
        val decision = GroupBootstrapAdmissionSupport.evaluateEdgeReadyRetry(
            GroupBootstrapAdmissionSupport.EdgeReadyEvaluationInput(
                intent = intent,
                endpoint = endpoint,
                peerEdgeReady = true,
                authorityAdmissible = true,
                isInviteProducer = true,
                admissionIncomplete = true,
                cooldownElapsed = true
            )
        )
        val invite = decision as GroupBootstrapAdmissionSupport.EdgeReadyDecision.IssueInvite
        assertEquals("M02", invite.moduleId)
        assertEquals(endpoint, invite.endpoint)
    }

    @Test
    fun evaluateEdgeReadyRetry_defersWhenAuthorityNotAdmissible() {
        val intent = GroupBootstrapAdmissionSupport.create("CH-01", "M02", "DISCOVERED_NO_SESSION")
        val decision = GroupBootstrapAdmissionSupport.evaluateEdgeReadyRetry(
            GroupBootstrapAdmissionSupport.EdgeReadyEvaluationInput(
                intent = intent,
                endpoint = EndpointAddress(ModuleId("M02"), EndpointId("E02")),
                peerEdgeReady = true,
                authorityAdmissible = false,
                isInviteProducer = true,
                admissionIncomplete = true,
                cooldownElapsed = true
            )
        )
        val deferred = decision as GroupBootstrapAdmissionSupport.EdgeReadyDecision.Deferred
        assertEquals("AUTHORITY_NOT_ADMISSIBLE", deferred.reason)
    }

    @Test
    fun evaluateEdgeReadyRetry_noActionWhenInviteAlreadySent() {
        val intent = GroupBootstrapAdmissionSupport.markInviteSent(
            GroupBootstrapAdmissionSupport.create("CH-01", "M02", "DISCOVERED_NO_SESSION"),
            "grp:CH-01"
        )
        val decision = GroupBootstrapAdmissionSupport.evaluateEdgeReadyRetry(
            GroupBootstrapAdmissionSupport.EdgeReadyEvaluationInput(
                intent = intent,
                endpoint = EndpointAddress(ModuleId("M02"), EndpointId("E02")),
                peerEdgeReady = true,
                authorityAdmissible = true,
                isInviteProducer = true,
                admissionIncomplete = true,
                cooldownElapsed = true
            )
        )
        assertEquals(GroupBootstrapAdmissionSupport.EdgeReadyDecision.NoAction, decision)
    }

    @Test
    fun shouldSuppressGroupJoinFallback_whenIntentWaiting() {
        val session = TalkbackSession(
            id = "grp:CH-01",
            local = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            type = SessionType.GROUP,
            channelId = "CH-01"
        )
        val waiting = GroupBootstrapAdmissionSupport.markWaiting(
            GroupBootstrapAdmissionSupport.create("CH-01", "M02", "DISCOVERED_NO_SESSION"),
            "WAITING_EDGE_NOT_READY:LOCAL_NOT_BIDIRECTIONAL"
        )
        assertTrue(
            GroupBootstrapAdmissionSupport.shouldSuppressGroupJoinFallback(waiting, session, "M02")
        )
    }

    @Test
    fun shouldSuppressGroupJoinFallback_falseAfterAccepted() {
        val session = TalkbackSession(
            id = "grp:CH-01",
            local = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            type = SessionType.GROUP,
            channelId = "CH-01"
        ).apply {
            groupMembers = listOf(EndpointAddress(ModuleId("M02"), EndpointId("E02")))
        }
        val accepted = GroupBootstrapAdmissionSupport.markAccepted(
            GroupBootstrapAdmissionSupport.create("CH-01", "M02", "DISCOVERED_NO_SESSION")
        )
        assertFalse(
            GroupBootstrapAdmissionSupport.shouldSuppressGroupJoinFallback(accepted, session, "M02")
        )
    }

    @Test
    fun inviteSentWhileAdmissionIncomplete_blocksEdgeReadySdpRetry() {
        val session = TalkbackSession(
            id = "grp:CH-01",
            local = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            type = SessionType.GROUP,
            channelId = "CH-01"
        ).apply {
            groupMembers = listOf(EndpointAddress(ModuleId("M02"), EndpointId("E02")))
            pendingInviteeEndpoints["M02"] = EndpointAddress(ModuleId("M02"), EndpointId("E02"))
        }
        val intent = GroupBootstrapAdmissionSupport.markInviteSent(
            GroupBootstrapAdmissionSupport.create("CH-01", "M02", "DISCOVERED_NO_SESSION"),
            "grp:CH-01"
        )
        assertTrue(GroupBootstrapAdmissionSupport.peerAdmissionIncomplete(session, "M02"))
        assertFalse(GroupBootstrapAdmissionSupport.eligibleForEdgeReadyRetry(intent))
        val decision = GroupBootstrapAdmissionSupport.evaluateEdgeReadyRetry(
            GroupBootstrapAdmissionSupport.EdgeReadyEvaluationInput(
                intent = intent,
                endpoint = EndpointAddress(ModuleId("M02"), EndpointId("E02")),
                peerEdgeReady = true,
                authorityAdmissible = true,
                isInviteProducer = true,
                admissionIncomplete = true,
                cooldownElapsed = true
            )
        )
        assertEquals(GroupBootstrapAdmissionSupport.EdgeReadyDecision.NoAction, decision)
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
