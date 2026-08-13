package com.talkback.core.session

import com.talkback.core.model.GroupSessionPayload
import com.talkback.core.model.MeshSessionMode
import com.talkback.core.model.MembershipSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInviteSemanticSupportTest {

    private fun payload(
        sdp: String = "",
        rejoin: Boolean = false,
        sessionMode: MeshSessionMode = MeshSessionMode.GROUP,
        membershipSnapshot: MembershipSnapshot? = null
    ) = GroupSessionPayload(
        sdp = sdp,
        channelId = "CH-01",
        members = listOf("M01-E01", "M03-E03"),
        initiatorModuleId = "M01",
        floorAuthorityModuleId = "M01",
        sessionMode = sessionMode,
        rejoin = rejoin,
        membershipSnapshot = membershipSnapshot
    )

    @Test
    fun classify_membershipSnapshotOnly_blankSdp() {
        val semantic = GroupInviteSemanticSupport.classify(
            payload(
                sdp = "",
                membershipSnapshot = MembershipSnapshot(
                    rosterEpoch = 2L,
                    anchorEpoch = 0L,
                    members = listOf("M01-E01", "M03-E03")
                )
            )
        )
        assertEquals(GroupInvitePayloadSemantic.MEMBERSHIP_SNAPSHOT_ONLY, semantic)
        assertFalse(GroupInviteSemanticSupport.triggersBootstrapAdmissionAccounting(semantic))
    }

    @Test
    fun classify_bootstrapSdp_groupWithoutRejoin() {
        val semantic = GroupInviteSemanticSupport.classify(
            payload(sdp = "v=0", rejoin = false)
        )
        assertEquals(GroupInvitePayloadSemantic.BOOTSTRAP_SDP_INVITE, semantic)
        assertTrue(GroupInviteSemanticSupport.triggersBootstrapAdmissionAccounting(semantic))
    }

    @Test
    fun classify_pairwiseMeshSdp_groupWithRejoin() {
        val semantic = GroupInviteSemanticSupport.classify(
            payload(sdp = "v=0", rejoin = true)
        )
        assertEquals(GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE, semantic)
        assertFalse(GroupInviteSemanticSupport.triggersBootstrapAdmissionAccounting(semantic))
    }

    @Test
    fun classify_rejoinSdp_conferenceMode() {
        val semantic = GroupInviteSemanticSupport.classify(
            payload(sdp = "v=0", rejoin = false, sessionMode = MeshSessionMode.CONFERENCE)
        )
        assertEquals(GroupInvitePayloadSemantic.REJOIN_SDP_INVITE, semantic)
        assertFalse(GroupInviteSemanticSupport.triggersBootstrapAdmissionAccounting(semantic))
    }

    @Test
    fun classify_blankSdpWithoutSnapshot_notBootstrapAccounting() {
        val semantic = GroupInviteSemanticSupport.classify(payload(sdp = ""))
        assertEquals(GroupInvitePayloadSemantic.REJOIN_SDP_INVITE, semantic)
        assertFalse(GroupInviteSemanticSupport.triggersBootstrapAdmissionAccounting(semantic))
    }
}
