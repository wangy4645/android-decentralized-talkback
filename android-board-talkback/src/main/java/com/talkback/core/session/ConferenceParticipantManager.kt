package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.ModuleId

/**
 * Owns conference participant lifecycle state (invite, media, roster, everConnected).
 * Topology facts such as [TalkbackSession.meshCompletedModules] stay outside this manager.
 * Prune **decisions** belong to reachability/membership; this type only [applyPrune].
 */
class ConferenceParticipantManager {

    private data class SessionState(
        val local: EndpointAddress,
        val roster: MutableList<EndpointAddress> = mutableListOf(),
        val participants: MutableMap<String, ParticipantState> = linkedMapOf(),
        val everConnectedModules: MutableSet<String> = linkedSetOf(),
        val leftMemberEndpoints: MutableMap<String, EndpointAddress> = linkedMapOf()
    )

    private val sessions = linkedMapOf<String, SessionState>()

    fun initSession(sessionId: String, local: EndpointAddress, members: List<EndpointAddress>) {
        val state = SessionState(local)
        state.roster.addAll(members)
        members.forEach { member ->
            if (member.moduleId != local.moduleId) {
                state.participants.getOrPut(member.moduleId.value) {
                    ParticipantState(member.moduleId)
                }
            }
        }
        sessions[sessionId] = state
    }

    fun removeSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun hasSession(sessionId: String): Boolean = sessionId in sessions

    fun roster(sessionId: String): List<EndpointAddress> =
        sessions[sessionId]?.roster?.toList() ?: emptyList()

    fun remoteModuleIds(sessionId: String, localModuleId: ModuleId): Set<String> =
        roster(sessionId)
            .asSequence()
            .map { it.moduleId.value }
            .filter { it != localModuleId.value }
            .toSet()

    fun participant(sessionId: String, moduleId: String): ParticipantState {
        val state = sessions.getValue(sessionId)
        return state.participants.getOrPut(moduleId) {
            ParticipantState(ModuleId(moduleId))
        }
    }

    fun participants(sessionId: String): Collection<ParticipantState> =
        sessions[sessionId]?.participants?.values ?: emptyList()

    fun containsParticipant(sessionId: String, moduleId: String): Boolean =
        sessions[sessionId]?.participants?.containsKey(moduleId) == true

    /** Read-only; does not create or repair participant records. */
    fun participantMedia(sessionId: String, moduleId: String): MediaState =
        sessions[sessionId]?.participants?.get(moduleId)?.media ?: MediaState.NONE

    fun syncParticipantsFromMembers(sessionId: String, localModuleId: ModuleId) {
        val state = sessions[sessionId] ?: return
        state.roster.forEach { member ->
            val id = member.moduleId.value
            if (id == localModuleId.value) return@forEach
            participant(sessionId, id)
        }
    }

    fun addToRoster(sessionId: String, endpoints: List<EndpointAddress>) {
        val state = sessions[sessionId] ?: return
        val existing = state.roster.map { it.key }.toSet()
        endpoints.forEach { remote ->
            if (remote.key !in existing) {
                state.roster.add(remote)
                participant(sessionId, remote.moduleId.value)
            }
        }
    }

    fun replaceRoster(sessionId: String, members: List<EndpointAddress>) {
        val state = sessions[sessionId] ?: return
        state.roster.clear()
        state.roster.addAll(members)
        syncParticipantsFromMembers(sessionId, state.local.moduleId)
    }

    /**
     * Membership-authority roster: every listed remote is in-meeting.
     *
     * Fills [MediaState.NONE] as [MediaState.CONNECTED] so UI can show the peer as
     * conference-present. This is membership presence, not local WebRTC health:
     * a missing spoke-spoke PC must not be projected as [MediaState.CONNECTING].
     * Existing CONNECTING / RECONNECTING / FAILED / CONNECTED are left unchanged
     * so Host↔member media facts are not overwritten.
     */
    fun applyCanonicalMembershipRoster(
        sessionId: String,
        members: List<EndpointAddress>,
        localModuleId: ModuleId,
    ) {
        replaceRoster(sessionId, members)
        members.forEach { endpoint ->
            val id = endpoint.moduleId.value
            if (id == localModuleId.value) return@forEach
            participant(sessionId, id).apply {
                if (invite != InviteState.ACCEPTED) {
                    invite = InviteState.ACCEPTED
                }
                if (media == MediaState.NONE) {
                    media = MediaState.CONNECTED
                    lastMediaChangeMs = System.currentTimeMillis()
                }
            }
        }
    }

    fun leftMemberEndpoints(sessionId: String): MutableMap<String, EndpointAddress>? =
        sessions[sessionId]?.leftMemberEndpoints

    fun onInviteSent(
        sessionId: String,
        moduleId: String,
        invitedAtMs: Long,
        forReconnect: Boolean
    ) {
        participant(sessionId, moduleId).apply {
            invite = InviteState.INVITING
            this.invitedAtMs = invitedAtMs
            if (forReconnect) {
                media = MediaState.RECONNECTING
            }
        }
    }

    fun onInviteAccepted(sessionId: String, moduleId: String) {
        participant(sessionId, moduleId).apply {
            invite = InviteState.ACCEPTED
            media = MediaState.CONNECTING
            lastMediaChangeMs = System.currentTimeMillis()
        }
    }

    fun onMediaConnected(sessionId: String, moduleId: String) {
        sessions[sessionId]?.everConnectedModules?.add(moduleId)
        participant(sessionId, moduleId).apply {
            if (invite == InviteState.INVITING || invite == InviteState.RINGING) {
                invite = InviteState.ACCEPTED
            }
            media = MediaState.CONNECTED
            lastMediaChangeMs = System.currentTimeMillis()
        }
    }

    fun onLateJoin(sessionId: String, moduleId: String, endpoint: EndpointAddress) {
        addToRoster(sessionId, listOf(endpoint))
        onInviteAccepted(sessionId, moduleId)
    }

    fun updateMediaState(sessionId: String, moduleId: String, media: MediaState) {
        if (!containsParticipant(sessionId, moduleId)) return
        participant(sessionId, moduleId).apply {
            this.media = media
            lastMediaChangeMs = System.currentTimeMillis()
        }
    }

    fun updateInviteState(sessionId: String, moduleId: String, invite: InviteState) {
        if (!containsParticipant(sessionId, moduleId)) return
        participant(sessionId, moduleId).invite = invite
    }

    fun wasEverConnected(sessionId: String, moduleId: String): Boolean =
        sessions[sessionId]?.everConnectedModules?.contains(moduleId) == true

    /**
     * Apply an externally decided prune. Returns the removed endpoint when pruned.
     */
    fun applyPrune(sessionId: String, moduleId: String): EndpointAddress? {
        val state = sessions[sessionId] ?: return null
        if (moduleId == state.local.moduleId.value) return null
        val endpoint = state.roster.firstOrNull { it.moduleId.value == moduleId } ?: return null
        state.leftMemberEndpoints[moduleId] = endpoint
        state.roster.removeAll { it.moduleId.value == moduleId }
        state.participants.remove(moduleId)
        return endpoint
    }

    fun evictInvitee(
        sessionId: String,
        moduleId: String,
        inviteState: InviteState
    ): Boolean {
        val state = sessions[sessionId] ?: return false
        if (moduleId == state.local.moduleId.value) return false
        state.roster.firstOrNull { it.moduleId.value == moduleId }?.let { endpoint ->
            state.leftMemberEndpoints[moduleId] = endpoint
        }
        state.roster.removeAll { it.moduleId.value == moduleId }
        if (containsParticipant(sessionId, moduleId)) {
            participant(sessionId, moduleId).invite = inviteState
            state.participants.remove(moduleId)
        }
        return true
    }

    fun snapshot(sessionId: String, localModuleId: ModuleId): ConferenceSnapshot {
        val state = sessions[sessionId]
            ?: return ConferenceSnapshot(emptyList(), emptyList(), emptySet())
        val memberViews = state.roster.mapNotNull { member ->
            val id = member.moduleId.value
            if (id == localModuleId.value) return@mapNotNull null
            val ps = state.participants[id]
            MemberView(
                key = member.key,
                moduleId = id,
                invite = ps?.invite ?: InviteState.NONE,
                media = ps?.media ?: MediaState.NONE
            )
        }
        return ConferenceSnapshot(
            roster = state.roster.toList(),
            memberViews = memberViews,
            everConnectedModules = state.everConnectedModules.map { ModuleId(it) }.toSet()
        )
    }
}
