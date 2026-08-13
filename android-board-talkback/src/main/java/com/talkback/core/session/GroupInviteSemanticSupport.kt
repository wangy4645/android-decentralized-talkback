package com.talkback.core.session

import com.talkback.core.model.GroupSessionPayload
import com.talkback.core.model.MeshSessionMode

/**
 * Classifies GROUP_INVITE payload semantics before admission accounting.
 * Field combinations are implementation details here — not admission-layer contracts.
 */
object GroupInviteSemanticSupport {

    fun classify(payload: GroupSessionPayload): GroupInvitePayloadSemantic {
        if (payload.membershipSnapshot != null && payload.sdp.isBlank()) {
            return GroupInvitePayloadSemantic.MEMBERSHIP_SNAPSHOT_ONLY
        }
        if (payload.sdp.isBlank()) {
            return GroupInvitePayloadSemantic.REJOIN_SDP_INVITE
        }
        if (payload.sessionMode != MeshSessionMode.GROUP) {
            return GroupInvitePayloadSemantic.REJOIN_SDP_INVITE
        }
        if (payload.rejoin) {
            return GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE
        }
        return GroupInvitePayloadSemantic.BOOTSTRAP_SDP_INVITE
    }

    fun triggersBootstrapAdmissionAccounting(semantic: GroupInvitePayloadSemantic): Boolean =
        semantic == GroupInvitePayloadSemantic.BOOTSTRAP_SDP_INVITE
}
