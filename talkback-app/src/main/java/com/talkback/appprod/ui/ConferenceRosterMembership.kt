package com.talkback.appprod.ui

import com.talkback.app.TalkbackSessionSnapshot
import com.talkback.core.session.ConferenceParticipantDisplayState
import com.talkback.core.session.ConferenceTopologyMode
import com.talkback.core.session.CppEvidence
import com.talkback.core.session.CppMediaRelation
import com.talkback.core.session.InviteState
import com.talkback.core.session.MediaState
import com.talkback.core.session.CppMembership

/** Joined spokes without a local edge. Membership alone makes no claim about audio. */
internal fun joinedIndirectSpokes(session: TalkbackSessionSnapshot?, localId: String): Set<String> {
    val topology = session?.conferenceTopologySnapshot ?: return emptySet()
    val presence = session.conferencePresenceProjection ?: return emptySet()
    if (topology.conferenceId != session.sessionId || topology.topologyMode != ConferenceTopologyMode.ANCHOR) {
        return emptySet()
    }
    val spokes = topology.actualMediaEdges.filter { it.anchorModuleId == topology.anchorId }
        .mapTo(mutableSetOf()) { it.remoteModuleId }
    if (!spokes.remove(localId)) return emptySet()
    val members = session.memberViews.associateBy { it.moduleId }
    val visible = session.visibleParticipants.associateBy { it.moduleId }
    return presence.participants.filter { record ->
        val id = record.moduleId
        val display = visible[id]?.displayState
        val member = members[id]
        id in spokes && member?.invite == InviteState.ACCEPTED &&
            (display != null || member.media == MediaState.NONE) && record.membership == CppMembership.JOINED &&
            record.evidence != CppEvidence.STALE && record.mediaRelation != CppMediaRelation.DEGRADED &&
            id !in presence.recoveringPeers && id !in presence.mediaUnavailablePeers &&
            (display == null || display == ConferenceParticipantDisplayState.VISIBLE_CONNECTING ||
                display == ConferenceParticipantDisplayState.VISIBLE_CONNECTED)
    }.mapTo(mutableSetOf()) { it.moduleId }
}
