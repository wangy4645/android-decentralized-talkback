package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.GroupSessionPayload
import com.talkback.core.model.MeshSessionMode
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0065 Patch-1.1 — PERMIT / DEFER / DENY admission decision split. */
class Adr0065Patch11AdmissionDecisionTest {

    private fun endpoint(moduleId: String) =
        EndpointAddress(ModuleId(moduleId), EndpointId("E01"))

    private fun groupSession(
        localId: String,
        memberIds: List<String>,
        rosterEpoch: Long = 1L,
        reconcileObligations: Boolean = true,
    ): TalkbackSession {
        val session = TalkbackSession(
            id = "grp:CH-01",
            type = SessionType.GROUP,
            local = endpoint(localId),
            channelId = "CH-01",
        )
        session.accepted = true
        session.rosterEpoch = rosterEpoch
        GroupMembershipSupport.applyGroupMembersList(
            session,
            memberIds.map { endpoint(it) },
        )
        if (reconcileObligations) {
            PairwiseMeshAdmissionSupport.reconcile(session)
        }
        return session
    }

    private fun sdpPayload(
        members: List<String>,
        rosterEpoch: Long,
        offerLineageId: String = "GM9",
        sdp: String = "v=0",
        sessionId: String = "grp:CH-01",
    ): GroupSessionPayload {
        val hash = GroupMembershipSupport.memberHash("CH-01", rosterEpoch, members)
        return GroupSessionPayload(
            sdp = sdp,
            channelId = "CH-01",
            members = members.map { "$it-E01" },
            initiatorModuleId = "M02",
            floorAuthorityModuleId = "M02",
            sessionMode = MeshSessionMode.GROUP,
            rejoin = true,
            rosterEpoch = rosterEpoch,
            memberHash = hash,
            offerLineageId = offerLineageId,
        )
    }

    @Test
    fun m04Regression_noExpectedEdge_defersWithoutConsume() {
        val session = groupSession(
            localId = "M04",
            memberIds = listOf("M02", "M03"),
            rosterEpoch = 1L,
            reconcileObligations = false,
        )
        val payload = sdpPayload(
            members = listOf("M02", "M03"),
            rosterEpoch = 1L,
            offerLineageId = "GM9",
        )
        val decision = GroupInviteMediaAdmissionSupport.evaluate(
            session = session,
            inviteSessionId = session.id,
            payload = payload,
            callerModuleId = "M02",
        )
        assertEquals(GroupInviteMediaAdmissionSupport.Decision.DeferEdgeNotReady, decision)
        assertFalse(GroupInviteMediaAdmissionSupport.shouldTerminalHandleInvite(decision))
    }

    @Test
    fun gm9Mismatch_expectedEdgeExists_permits() {
        val session = groupSession("M02", listOf("M01", "M02"), rosterEpoch = 2L)
        val payload = sdpPayload(
            members = listOf("M01", "M02", "M04"),
            rosterEpoch = 2L,
            offerLineageId = "GM9",
        )
        assertTrue(
            GroupInviteMembershipMismatchSupport.evaluate(session, payload)
                is GroupInviteMembershipMismatchSupport.Decision.MismatchObserved
        )
        val decision = GroupInviteMediaAdmissionSupport.evaluate(
            session = session,
            inviteSessionId = session.id,
            payload = payload,
            callerModuleId = "M01",
        )
        assertEquals(GroupInviteMediaAdmissionSupport.Decision.PermitSdpProcessing, decision)
        assertTrue(GroupInviteMediaAdmissionSupport.shouldTerminalHandleInvite(decision))
    }

    @Test
    fun wrongSessionId_deniesAndConsumes() {
        val session = groupSession("M02", listOf("M02", "M03"), rosterEpoch = 1L)
        val payload = sdpPayload(
            members = listOf("M02", "M03"),
            rosterEpoch = 1L,
        )
        val decision = GroupInviteMediaAdmissionSupport.evaluate(
            session = session,
            inviteSessionId = "grp:OTHER",
            payload = payload,
            callerModuleId = "M03",
        )
        assertEquals(
            GroupInviteMediaAdmissionSupport.DenyReason.INVALID_SESSION,
            (decision as GroupInviteMediaAdmissionSupport.Decision.DenySdpProcessing).reason,
        )
        assertTrue(GroupInviteMediaAdmissionSupport.shouldTerminalHandleInvite(decision))
    }

    @Test
    fun wrongRole_deniesAndConsumes() {
        val session = groupSession("M01", listOf("M01", "M02"), rosterEpoch = 2L)
        val payload = sdpPayload(
            members = listOf("M01", "M02"),
            rosterEpoch = 2L,
        )
        val decision = GroupInviteMediaAdmissionSupport.evaluate(
            session = session,
            inviteSessionId = session.id,
            payload = payload,
            callerModuleId = "M02",
        )
        assertEquals(
            GroupInviteMediaAdmissionSupport.DenyReason.WRONG_ROLE,
            (decision as GroupInviteMediaAdmissionSupport.Decision.DenySdpProcessing).reason,
        )
        assertTrue(GroupInviteMediaAdmissionSupport.shouldTerminalHandleInvite(decision))
    }

    @Test
    fun fencedBinding_deniesAndConsumes() {
        val session = groupSession("M02", listOf("M01", "M02"), rosterEpoch = 2L)
        GroupPcLineageBindingSupport.recordAnswererBinding(
            session = session,
            remoteModuleId = "M01",
            offerLineageId = "GM8",
            pcLineage = 3L,
        )
        GroupPcLineageBindingSupport.binding(session, "M01", "GM8")!!.let { binding ->
            GroupPcLineageBindingSupport.fenceBinding(session, binding)
        }
        val payload = sdpPayload(
            members = listOf("M01", "M02"),
            rosterEpoch = 2L,
            offerLineageId = "GM8",
        )
        val decision = GroupInviteMediaAdmissionSupport.evaluate(
            session = session,
            inviteSessionId = session.id,
            payload = payload,
            callerModuleId = "M01",
        )
        assertEquals(
            GroupInviteMediaAdmissionSupport.DenyReason.FENCED_BINDING,
            (decision as GroupInviteMediaAdmissionSupport.Decision.DenySdpProcessing).reason,
        )
        assertTrue(GroupInviteMediaAdmissionSupport.shouldTerminalHandleInvite(decision))
    }
}
