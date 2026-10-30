package com.talkback.core.conference.session.profile01

import com.talkback.core.conference.session.profile01.wire.Profile01DerivedMediaKeyMaterial
import java.util.concurrent.ConcurrentHashMap

/**
 * Decrypted MEDIA_KEY_PACKAGE material keyed by conference + mediaKeyEpoch.
 */
class Profile01SessionMediaSupplementRegistry {
    private val byConference = ConcurrentHashMap<String, ConcurrentHashMap<Long, Profile01DerivedMediaKeyMaterial>>()

    fun put(material: Profile01DerivedMediaKeyMaterial) {
        byConference
            .getOrPut(material.conferenceId) { ConcurrentHashMap() }
            .put(material.mediaKeyEpoch, material)
    }

    fun lookup(conferenceId: String, mediaKeyEpoch: Long): Profile01DerivedMediaKeyMaterial? =
        byConference[conferenceId]?.get(mediaKeyEpoch)

    fun resolveSupplement(
        conferenceId: String,
        mediaKeyEpoch: Long,
        channelId: String,
        fallback: Profile01SessionMediaSupplement,
    ): Profile01SessionMediaSupplement {
        val derived = lookup(conferenceId, mediaKeyEpoch)
        return derived?.toSessionSupplement(channelId) ?: fallback
    }

    fun clear() {
        byConference.clear()
    }
}
