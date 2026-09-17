package com.talkback.core.session

import com.talkback.core.model.ModuleId

/**
 * OPS-08: authoritative host-vs-peer classification for CONFERENCE inbound GROUP_ACCEPT.
 *
 * Host realization accept (host receives participant answer to host GROUP_INVITE) uses CR lineage
 * and OPS-07 fail-closed. Peer mesh accept (participant receives participant GROUP_JOIN answer)
 * uses the legacy peer apply path and must not enter host CR guards.
 */
object ConferenceGroupAcceptDispatchSupport {

    fun usesHostRealizationAccept(session: TalkbackSession, localModuleId: ModuleId): Boolean =
        session.type == SessionType.CONFERENCE &&
            session.accepted &&
            session.initiatorModuleId == localModuleId
}
