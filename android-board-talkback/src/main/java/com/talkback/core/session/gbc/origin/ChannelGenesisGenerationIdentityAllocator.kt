package com.talkback.core.session.gbc.origin

import java.security.MessageDigest

/**
 * CSO-IA-T2: per-channel genesis generation identity — create-once, restart-safe.
 */
class ChannelGenesisGenerationIdentityAllocator(
    private val persistence: ChannelGenesisIdentityPersistence,
) {
    fun allocateOrLoad(channelId: String): String {
        val existing = persistence.load(channelId)
        if (existing != null) return existing
        val identity = stableGenesisIdentity(channelId)
        persistence.save(channelId, identity)
        return identity
    }

    fun peek(channelId: String): String? = persistence.load(channelId)

    companion object {
        private const val DOMAIN = "talkback-gbc-genesis-v1"

        fun stableGenesisIdentity(channelId: String): String {
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest("$DOMAIN|$channelId".toByteArray(Charsets.UTF_8))
            return digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}

interface ChannelGenesisIdentityPersistence {
    fun load(channelId: String): String?

    fun save(
        channelId: String,
        generationIdentity: String,
    )
}
