package com.talkback.core.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-channel, per-plane authority digest observations.
 * A GROUP HELLO must not replace a CONFERENCE observation on the same channel.
 */
class ChannelAuthorityDigestStore {
    data class Key(
        val channelId: String,
        val plane: MembershipAuthorityPlane
    )

    private val byKey = ConcurrentHashMap<Key, AuthorityDigestObservation>()

    fun get(channelId: String, plane: MembershipAuthorityPlane): AuthorityDigestObservation? =
        byKey[Key(channelId, plane)]

    fun put(observation: AuthorityDigestObservation): AuthorityDigestObservation? {
        val key = Key(observation.channelId, observation.plane)
        val previous = byKey[key]
        byKey[key] = observation
        return previous
    }

    fun clear(channelId: String, plane: MembershipAuthorityPlane): AuthorityDigestObservation? =
        byKey.remove(Key(channelId, plane))

    fun contains(channelId: String, plane: MembershipAuthorityPlane): Boolean =
        byKey.containsKey(Key(channelId, plane))
}
