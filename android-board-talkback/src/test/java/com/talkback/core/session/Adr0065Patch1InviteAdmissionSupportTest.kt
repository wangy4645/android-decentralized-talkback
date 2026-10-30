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

/** ADR-0065 Patch-1 — membership/media admission separation for SDP GROUP_INVITE. */
class Adr0065Patch1InviteAdmissionSupportTest {

    private fun endpoint(moduleId: String) =
        EndpointAddress(ModuleId(moduleId), EndpointId("E01"))

    private fun groupSession(
        localId: String,
        memberIds: List<String>,
        rosterEpoch: Long = 2L,
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
        PairwiseMeshAdmissionSupport.reconcile(session)
        return session
    }

    private fun sdpPayload(
        members: List<String>,
        rosterEpoch: Long,
        offerLineageId: String = "GM9",
        sdp: String = "v=0",
    ): GroupSessionPayload {
        val memberKeys = members.map { "$it-E01" }
        val hash = GroupMembershipSupport.memberHash("CH-01", rosterEpoch, members)
        return GroupSessionPayload(
            sdp = sdp,
            channelId = "CH-01",
            members = memberKeys,
            initiatorModuleId = "M01",
            floorAuthorityModuleId = "M01",
            sessionMode = MeshSessionMode.GROUP,
            rejoin = true,
            rosterEpoch = rosterEpoch,
            memberHash = hash,
            offerLineageId = offerLineageId,
        )
    }

    @Test
    fun membership_sameEpochDifferentMembers_mismatchObserved() {
        val session = groupSession("M02", listOf("M01", "M02"), rosterEpoch = 2L)
        val payload = sdpPayload(
            members = listOf("M01", "M02", "M04"),
            rosterEpoch = 2L,
        )
        val decision = GroupInviteMembershipMismatchSupport.evaluate(session, payload)
        assertTrue(decision is GroupInviteMembershipMismatchSupport.Decision.MismatchObserved)
        val observed = decision as GroupInviteMembershipMismatchSupport.Decision.MismatchObserved
        assertTrue(observed.resyncRequired)
        assertEquals(listOf("M01", "M02"), observed.localMemberIds)
        assertEquals(listOf("M01", "M02", "M04"), observed.incomingMemberIds)
    }

    @Test
    fun membership_alignedMembers_noMismatch() {
        val session = groupSession("M02", listOf("M01", "M02", "M03"), rosterEpoch = 3L)
        val payload = sdpPayload(
            members = listOf("M01", "M02", "M03"),
            rosterEpoch = 3L,
        )
        assertTrue(
            GroupInviteMembershipMismatchSupport.evaluate(session, payload)
                is GroupInviteMembershipMismatchSupport.Decision.NoMismatch
        )
    }

    @Test
    fun membership_mismatch_doesNotBlockMediaAdmission() {
        val session = groupSession("M02", listOf("M01", "M02"), rosterEpoch = 2L)
        val payload = sdpPayload(
            members = listOf("M01", "M02", "M04"),
            rosterEpoch = 2L,
        )
        assertTrue(
            GroupInviteMembershipMismatchSupport.evaluate(session, payload)
                is GroupInviteMembershipMismatchSupport.Decision.MismatchObserved
        )
        assertEquals(
            GroupInviteMediaAdmissionSupport.Decision.PermitSdpProcessing,
            GroupInviteMediaAdmissionSupport.evaluate(
                session = session,
                inviteSessionId = session.id,
                payload = payload,
                callerModuleId = "M01",
            )
        )
    }

    @Test
    fun media_missingOfferLineage_denied() {
        val session = groupSession("M02", listOf("M01", "M02"), rosterEpoch = 2L)
        val payload = sdpPayload(
            members = listOf("M01", "M02"),
            rosterEpoch = 2L,
            offerLineageId = "",
        )
        val decision = GroupInviteMediaAdmissionSupport.evaluate(
            session = session,
            inviteSessionId = session.id,
            payload = payload,
            callerModuleId = "M01",
        )
        assertEquals(
            GroupInviteMediaAdmissionSupport.DenyReason.MALFORMED_SIGNAL,
            (decision as GroupInviteMediaAdmissionSupport.Decision.DenySdpProcessing).reason
        )
    }

    @Test
    fun media_offererSide_deniedNotAnswerer() {
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
            (decision as GroupInviteMediaAdmissionSupport.Decision.DenySdpProcessing).reason
        )
    }

    @Test
    fun media_fencedBinding_denied() {
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
            (decision as GroupInviteMediaAdmissionSupport.Decision.DenySdpProcessing).reason
        )
    }

    @Test
    fun resync_inFlightWithinBudget_skipsDispatch() {
        val nowMs = 10_000L
        val existing = MembershipResyncRecord(
            channelId = "CH-01",
            episodeId = GroupInviteMembershipResyncSupport.INVITE_MISMATCH_RESYNC_EPISODE_ID,
            authorityId = "M01",
            conferenceSessionId = "grp:CH-01",
            transportTrigger = GroupInviteMembershipResyncSupport.TRANSPORT_TRIGGER,
            state = MembershipResyncState.DISPATCHED,
            startedAtMs = 9_000L,
            lastDispatchAtMs = 9_000L,
            dispatchCount = 1,
        )
        assertEquals(
            GroupInviteMembershipResyncSupport.DispatchDecision.ALREADY_IN_FLIGHT,
            GroupInviteMembershipResyncSupport.evaluateDispatch(
                existing = existing,
                nowMs = nowMs,
                inFlightBudgetMs = 5_000L,
            )
        )
    }

    @Test
    fun resync_budgetExpired_allowsDispatch() {
        val existing = MembershipResyncRecord(
            channelId = "CH-01",
            episodeId = GroupInviteMembershipResyncSupport.INVITE_MISMATCH_RESYNC_EPISODE_ID,
            authorityId = "M01",
            conferenceSessionId = "grp:CH-01",
            transportTrigger = GroupInviteMembershipResyncSupport.TRANSPORT_TRIGGER,
            state = MembershipResyncState.DISPATCHED,
            startedAtMs = 1_000L,
            lastDispatchAtMs = 1_000L,
            dispatchCount = 1,
        )
        assertEquals(
            GroupInviteMembershipResyncSupport.DispatchDecision.DISPATCH,
            GroupInviteMembershipResyncSupport.evaluateDispatch(
                existing = existing,
                nowMs = 10_000L,
                inFlightBudgetMs = 5_000L,
            )
        )
    }

    @Test
    fun gm9ReplayScenario_mismatchParallelPermit() {
        val session = groupSession("M02", listOf("M01", "M02"), rosterEpoch = 2L)
        val payload = sdpPayload(
            members = listOf("M01", "M02", "M04"),
            rosterEpoch = 2L,
            offerLineageId = "GM9",
        )
        val membership = GroupInviteMembershipMismatchSupport.evaluate(session, payload)
        val media = GroupInviteMediaAdmissionSupport.evaluate(
            session = session,
            inviteSessionId = session.id,
            payload = payload,
            callerModuleId = "M01",
        )
        assertTrue(membership is GroupInviteMembershipMismatchSupport.Decision.MismatchObserved)
        assertEquals(GroupInviteMediaAdmissionSupport.Decision.PermitSdpProcessing, media)
        val observed = membership as GroupInviteMembershipMismatchSupport.Decision.MismatchObserved
        assertTrue(observed.resyncRequired)
        assertFalse(observed.incomingMemberIds == observed.localMemberIds)
    }
}
