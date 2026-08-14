package com.talkback.core.session

import com.talkback.core.model.TopologyDigest

/**
 * #190: shared membership digest materialization for HELLO publication and recovery convergence.
 * Uses rosterEpoch + [GroupMembershipSupport.memberHash] — not [ChannelManager] or
 * [TalkbackSession.channelMemberSnapshot].
 */
object MembershipDigestSupport {

    fun digest(
        channelId: String,
        rosterEpoch: Long,
        anchorEpoch: Long,
        meshGeneration: Long,
        rosterModuleIds: Collection<String>
    ): TopologyDigest = TopologyDigest(
        rosterEpoch = rosterEpoch,
        anchorEpoch = anchorEpoch,
        meshGeneration = meshGeneration,
        memberHash = GroupMembershipSupport.memberHash(channelId, rosterEpoch, rosterModuleIds)
    )

    fun digestFromGroupSession(session: TalkbackSession): TopologyDigest =
        TopologyDigest.fromSession(session)

    /** Conference-only path: roster module ids come from mesh/control-plane roster, not groupMembers. */
    fun digestFromConferenceRoster(
        conferenceSession: TalkbackSession,
        rosterModuleIds: Collection<String>
    ): TopologyDigest {
        val channelId = conferenceSession.channelId
            ?: return TopologyDigest.fromSession(conferenceSession)
        return digest(
            channelId = channelId,
            rosterEpoch = conferenceSession.rosterEpoch,
            anchorEpoch = conferenceSession.anchorEpoch,
            meshGeneration = conferenceSession.meshGeneration,
            rosterModuleIds = rosterModuleIds
        )
    }

    /**
     * Mirrors [com.talkback.app.TalkbackCoordinator.broadcastHello] membership digest session selection.
     * Accepted CONFERENCE wins over coexisting GROUP so HELLO authority matches conference recovery.
     */
    fun selectHelloDigestSession(sessions: Collection<TalkbackSession>): TalkbackSession? {
        sessions.firstOrNull {
            it.type == SessionType.CONFERENCE && it.accepted && it.channelId != null
        }?.let { return it }
        sessions.firstOrNull {
            it.type == SessionType.GROUP && it.accepted && it.channelId != null
        }?.let { return it }
        return sessions.firstOrNull {
            (it.type == SessionType.GROUP || it.type == SessionType.CONFERENCE) &&
                it.accepted &&
                it.channelId != null &&
                it.mediaTopology == GroupMediaTopology.ANCHOR
        }
    }

    fun selectGroupSessionOnChannel(
        sessions: Collection<TalkbackSession>,
        channelId: String
    ): TalkbackSession? = sessions.firstOrNull {
        it.type == SessionType.GROUP && it.accepted && it.channelId == channelId
    }

    /** Conference recovery always compares conference-scoped digest, never stale GROUP generation. */
    fun convergenceLocalDigest(
        conferenceSession: TalkbackSession,
        conferenceRosterModuleIds: Collection<String>
    ): TopologyDigest = digestFromConferenceRoster(conferenceSession, conferenceRosterModuleIds)

    fun convergenceLocalSessionId(conferenceSession: TalkbackSession): String = conferenceSession.id
}
