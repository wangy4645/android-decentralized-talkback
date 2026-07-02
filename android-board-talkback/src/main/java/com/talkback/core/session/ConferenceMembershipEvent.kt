package com.talkback.core.session

import com.talkback.core.model.EndpointAddress

/** ADR-0012: canonical Conference membership transitions (control plane). */
sealed class ConferenceMembershipEvent {
    data class PeerLeft(val moduleId: String) : ConferenceMembershipEvent()

    data class PeerReactivated(val remote: EndpointAddress) : ConferenceMembershipEvent()

    /** Host-authoritative roster snapshot (e.g. CONFERENCE_MESH_RECONCILE payload.members). */
    data class SnapshotCorrected(val members: List<EndpointAddress>) : ConferenceMembershipEvent()
}
