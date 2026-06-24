package com.talkback.core.model

import com.talkback.core.session.GroupMembershipSupport
import com.talkback.core.session.TalkbackSession

data class TopologyDigest(
    val rosterEpoch: Long,
    val anchorEpoch: Long,
    val meshGeneration: Long = 0L,
    val memberHash: Int = 0
) {
    companion object {
        fun fromSession(session: TalkbackSession): TopologyDigest = TopologyDigest(
            rosterEpoch = session.rosterEpoch,
            anchorEpoch = session.anchorEpoch,
            meshGeneration = session.meshGeneration,
            memberHash = GroupMembershipSupport.memberHashForSession(session)
        )
    }
}
