package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.qos.IceConnectivity

/**
 * Pure helpers for Distributed Talkgroup Membership (V1.2).
 * Coordinator supplies ICE liveness; these functions stay discovery-agnostic.
 */
object GroupMembershipSupport {

    const val INITIAL_ROSTER_EPOCH = 1L

    data class GroupMemberIdentityRebound(
        val oldEndpoint: EndpointAddress,
        val newEndpoint: EndpointAddress
    )

    fun membershipState(session: TalkbackSession, moduleId: String): GroupMemberReachability =
        session.membershipStateByModule[moduleId] ?: GroupMemberReachability.ONLINE

    fun isEvicted(session: TalkbackSession, moduleId: String): Boolean =
        membershipState(session, moduleId) == GroupMemberReachability.EVICTED

    fun isIceAlive(iceState: String?): Boolean = IceConnectivity.isConnected(iceState)

    /**
     * Active for control/media/transmit: ONLINE, or SUSPECT while ICE is still alive.
     */
    fun isActiveMember(
        session: TalkbackSession,
        moduleId: String,
        iceState: String?
    ): Boolean {
        if (moduleId == session.local.moduleId.value) return true
        return when (membershipState(session, moduleId)) {
            GroupMemberReachability.EVICTED -> false
            GroupMemberReachability.SUSPECT -> isIceAlive(iceState)
            GroupMemberReachability.ONLINE -> when {
                iceState == null -> true
                IceConnectivity.isNegotiating(iceState) -> true
                else -> isIceAlive(iceState)
            }
        }
    }

    fun canonicalMemberModuleIds(session: TalkbackSession): Set<ModuleId> =
        session.groupMembers
            .map { it.moduleId }
            .filter { !isEvicted(session, it.value) }
            .toSet()

    /** Canonical roster endpoints (excludes EVICTED); excludes pending invitees. */
    fun canonicalRosterEndpoints(session: TalkbackSession): List<EndpointAddress> =
        session.groupMembers.filter { !isEvicted(session, it.moduleId.value) }

    fun canonicalMemberKeys(session: TalkbackSession): List<String> =
        canonicalRosterEndpoints(session).map { it.key }

    fun activeMemberModuleIds(
        session: TalkbackSession,
        iceStateForModule: (String) -> String?
    ): Set<ModuleId> {
        val ids = linkedSetOf<ModuleId>()
        ids.add(session.local.moduleId)
        canonicalMemberModuleIds(session).forEach { moduleId ->
            if (moduleId == session.local.moduleId) return@forEach
            if (isActiveMember(session, moduleId.value, iceStateForModule(moduleId.value))) {
                ids.add(moduleId)
            }
        }
        return ids
    }

    fun bumpRosterEpoch(session: TalkbackSession): Long {
        session.rosterEpoch += 1L
        return session.rosterEpoch
    }

    fun ensureMembershipEntry(session: TalkbackSession, moduleId: String) {
        if (moduleId == session.local.moduleId.value) return
        if (moduleId !in session.membershipStateByModule) {
            session.membershipStateByModule[moduleId] = GroupMemberReachability.ONLINE
        }
    }

    fun markSuspect(session: TalkbackSession, moduleId: String, nowMs: Long) {
        if (moduleId == session.local.moduleId.value) return
        if (isEvicted(session, moduleId)) return
        if (membershipState(session, moduleId) == GroupMemberReachability.SUSPECT) return
        session.membershipStateByModule[moduleId] = GroupMemberReachability.SUSPECT
        session.suspectSinceMsByModule[moduleId] = nowMs
    }

    fun markOnline(session: TalkbackSession, moduleId: String) {
        if (moduleId == session.local.moduleId.value) return
        if (isEvicted(session, moduleId)) return
        session.membershipStateByModule[moduleId] = GroupMemberReachability.ONLINE
        session.suspectSinceMsByModule.remove(moduleId)
    }

    fun markEvicted(session: TalkbackSession, moduleId: String) {
        if (moduleId == session.local.moduleId.value) return
        session.membershipStateByModule[moduleId] = GroupMemberReachability.EVICTED
        session.suspectSinceMsByModule.remove(moduleId)
    }

    fun syncMembershipFromGroupMembers(session: TalkbackSession) {
        val memberIds = session.groupMembers.map { it.moduleId.value }.toSet()
        memberIds.forEach { ensureMembershipEntry(session, it) }
        session.membershipStateByModule.keys.toList().forEach { id ->
            if (id !in memberIds && !isEvicted(session, id)) {
                session.membershipStateByModule.remove(id)
                session.suspectSinceMsByModule.remove(id)
            }
        }
    }

    fun applyGroupMembersList(session: TalkbackSession, members: List<EndpointAddress>) {
        session.groupMembers = members
        session.memberModules.clear()
        members.map { it.moduleId }.forEach { session.memberModules.add(it) }
        syncMembershipFromGroupMembers(session)
    }

    /**
     * R35: replace stale endpoint binding for [moduleId] when verified HELLO reports a new endpointId.
     * Single key per module — no merge of old and new keys.
     */
    fun replaceGroupMemberEndpoint(
        session: TalkbackSession,
        moduleId: String,
        endpointId: EndpointId
    ): GroupMemberIdentityRebound? {
        val existing = session.groupMembers.find { it.moduleId.value == moduleId } ?: return null
        if (existing.endpointId == endpointId) return null
        val newEndpoint = EndpointAddress(existing.moduleId, endpointId)
        session.groupMembers = session.groupMembers.map { member ->
            if (member.moduleId.value == moduleId) newEndpoint else member
        }
        session.pendingInviteeEndpoints[moduleId]?.let {
            session.pendingInviteeEndpoints[moduleId] = newEndpoint
        }
        syncMembershipFromGroupMembers(session)
        return GroupMemberIdentityRebound(existing, newEndpoint)
    }

    enum class MembershipSnapshotApplyResult {
        APPLIED,
        IGNORED_STALE,
        IGNORED_NOT_AUTHORITY
    }

    /**
     * Apply authority membership snapshot (control plane only — no media teardown).
     */
    fun applyMembershipSnapshot(
        session: TalkbackSession,
        rosterEpoch: Long,
        anchorEpoch: Long,
        members: List<EndpointAddress>,
        senderModuleId: String,
        authorityModuleId: String
    ): MembershipSnapshotApplyResult {
        val channelId = session.channelId ?: return MembershipSnapshotApplyResult.IGNORED_STALE
        if (senderModuleId != authorityModuleId) {
            return MembershipSnapshotApplyResult.IGNORED_NOT_AUTHORITY
        }
        val memberIds = members.map { it.moduleId.value }
        val remoteHash = memberHash(channelId, rosterEpoch, memberIds)
        val localHash = memberHashForSession(session)
        if (rosterEpoch == session.rosterEpoch && remoteHash == localHash) {
            return MembershipSnapshotApplyResult.IGNORED_STALE
        }
        applyGroupMembersList(session, members)
        session.memberModules.add(session.local.moduleId)
        session.rosterEpoch = rosterEpoch
        if (anchorEpoch > 0L) {
            session.anchorEpoch = anchorEpoch
        }
        memberIds.forEach { session.pendingInviteeEndpoints.remove(it) }
        return MembershipSnapshotApplyResult.APPLIED
    }

    /** FNV-1a 32-bit over channelId + rosterEpoch + sorted canonical module ids (no anchorEpoch). */
    fun memberHash(channelId: String, rosterEpoch: Long, canonicalModuleIds: Collection<String>): Int {
        val sorted = canonicalModuleIds.sorted()
        var hash = 0x811C9DC5.toInt()
        fun mix(value: Int) {
            hash = hash xor value
            hash *= 0x01000193
        }
        channelId.forEach { mix(it.code) }
        mix((rosterEpoch shr 32).toInt())
        mix(rosterEpoch.toInt())
        sorted.forEach { id ->
            id.forEach { ch -> mix(ch.code) }
            mix(0)
        }
        return hash
    }

    fun memberHashForSession(session: TalkbackSession): Int {
        val channelId = session.channelId ?: return 0
        val ids = canonicalMemberModuleIds(session).map { it.value }
        return memberHash(channelId, session.rosterEpoch, ids)
    }
}
