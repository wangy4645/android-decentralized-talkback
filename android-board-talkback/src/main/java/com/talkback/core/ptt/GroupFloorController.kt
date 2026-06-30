package com.talkback.core.ptt

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.FloorSnapshotDigest
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

    fun canPublishFloorSnapshot(session: TalkbackSession, localModuleId: String): Boolean =
        isFloorAuthority(session, localModuleId)

    fun shouldProcessFloorRequest(session: TalkbackSession, localModuleId: String): Boolean {
        if (session.type != SessionType.GROUP) return false
        return isFloorAuthority(session, localModuleId)
    }

    fun resolveFloorOwner(
        session: TalkbackSession,
        requesterKey: String
    ): EndpointAddress? {
        if (requesterKey.isBlank()) return null
        session.groupMembers.find { it.key == requesterKey }?.let { return it }
        val moduleId = moduleIdFromEndpointKey(requesterKey) ?: return null
        return session.groupMembers.find { it.moduleId.value.equals(moduleId, ignoreCase = true) }
    }

    /** Canonical key for floor payloads when roster binding differs from stale owner/requester key. */
    fun canonicalFloorOwnerKey(session: TalkbackSession): String? {
        val owner = session.floor.owner() ?: return null
        return resolveFloorOwner(session, owner.key)?.key ?: owner.key
    }

    fun canonicalRequester(session: TalkbackSession, requester: EndpointAddress): EndpointAddress =
        resolveFloorOwner(session, requester.key) ?: requester

    private fun moduleIdFromEndpointKey(key: String): String? {
        val dash = key.indexOf('-')
        return if (dash <= 0) {
            key.takeIf { it.isNotBlank() }?.uppercase()
        } else {
            key.substring(0, dash).uppercase()
        }
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

    /**
     * Apply authority floor snapshot from HELLO when [authorityModuleId] matches session authority.
     * Returns [SnapshotResult.DEFERRED] when ownerKey is set but not yet in canonical roster.
     */
    fun applyAuthorityFloorSnapshot(
        session: TalkbackSession,
        authorityModuleId: String,
        digest: FloorSnapshotDigest,
        onOwnerChanged: () -> Unit = {}
    ): SnapshotResult {
        if (session.type != SessionType.GROUP) return SnapshotResult.UNCHANGED
        val sessionAuthority = session.floorAuthorityModuleId?.value
            ?: session.initiatorModuleId?.value
            ?: return SnapshotResult.UNCHANGED
        if (authorityModuleId != sessionAuthority) return SnapshotResult.UNCHANGED
        val owner = digest.ownerKey?.let { resolveFloorOwner(session, it) }
        if (digest.ownerKey != null && owner == null) {
            return SnapshotResult.DEFERRED
        }
        val result = session.floor.applySnapshot(
            owner,
            digest.version,
            digest.epoch,
            digest.ownerPriority
        )
        if (result == SnapshotResult.OWNER_CHANGED) {
            onOwnerChanged()
        }
        return result
    }
}