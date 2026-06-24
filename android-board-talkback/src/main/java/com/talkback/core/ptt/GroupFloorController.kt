package com.talkback.core.ptt

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointPriority
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession

/**
 * Floor control rules for group sessions (initiator authority).
 */
object GroupFloorController {
    fun isFloorAuthority(session: TalkbackSession, localModuleId: String): Boolean {
        if (session.type != SessionType.GROUP) return true
        val authority = session.floorAuthorityModuleId ?: session.initiatorModuleId ?: return false
        return authority.value == localModuleId
    }

    fun shouldProcessFloorRequest(session: TalkbackSession, localModuleId: String): Boolean {
        if (session.type != SessionType.GROUP) return false
        return isFloorAuthority(session, localModuleId)
    }

    fun resolveFloorOwner(
        session: TalkbackSession,
        requesterKey: String
    ): EndpointAddress? {
        return session.groupMembers.find { it.key == requesterKey }
    }

    fun applyRemoteGrant(
        session: TalkbackSession,
        owner: EndpointAddress,
        floorVersion: Long,
        floorEpoch: Long,
        priority: EndpointPriority
    ) {
        session.floor.applyGrant(owner, floorVersion, floorEpoch, priority)
    }
}
