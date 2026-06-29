package com.talkback.core.presence

import com.talkback.core.audio.ModuleAudioMixer
import com.talkback.core.session.ConferenceSnapshot
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import com.talkback.core.session.usesFloorControl

/**
 * Read-only Presence projections (R9/R10). No side effects.
 */
object PresenceProjector {

    fun sessionSnapshot(
        session: TalkbackSession,
        conferenceSnap: ConferenceSnapshot? = null
    ): SessionPresenceSnapshot {
        val membership = conferenceSnap?.roster?.map { it.key }
            ?: session.groupMembers.map { it.key }.ifEmpty {
                listOfNotNull(session.local.key, session.remote?.key)
            }
        val protocolFloorOwnerKey = if (session.type.usesFloorControl()) {
            session.floor.owner()?.key
        } else {
            null
        }
        return SessionPresenceSnapshot(
            sessionId = session.id,
            type = session.type,
            protocolFloorOwnerKey = protocolFloorOwnerKey,
            membershipKeys = membership,
            rosterEpoch = session.rosterEpoch,
            disposition = session.disposition
        )
    }

    fun moduleSnapshot(
        moduleMixer: ModuleAudioMixer,
        localUplinkGrant: Boolean,
        iceByPeer: Map<String, String>,
        stackTopSessionId: String? = null
    ): ModulePresenceSnapshot = ModulePresenceSnapshot(
        localUplinkGrant = localUplinkGrant,
        activeCaptureEndpointKey = moduleMixer.activeCapture()?.key,
        iceByPeer = iceByPeer,
        stackTopSessionId = stackTopSessionId
    )

    /**
     * R11 / INVARIANT_F1: uplink grant must not be true unless protocol floor owner is local.
     */
    fun satisfiesInvariantF1(
        sessionPresence: SessionPresenceSnapshot,
        modulePresence: ModulePresenceSnapshot,
        localEndpointKey: String
    ): Boolean {
        if (!modulePresence.localUplinkGrant) return true
        if (sessionPresence.type != SessionType.GROUP) return true
        val owner = sessionPresence.protocolFloorOwnerKey ?: return false
        return owner == localEndpointKey
    }
}
