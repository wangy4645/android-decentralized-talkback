package com.talkback.core.webrtc

/**
 * P0 observation: which track is currently attached to an Anchor outbound audio sender.
 * Not a control surface.
 */
data class ProgramSenderSnapshot(
    val senderId: String,
    val currentTrackId: String,
    val expectedTrackId: String,
    val enabled: Boolean,
    val readyState: String,
    val lastReplaceAt: String = "none"
) {
    companion object {
        val NONE = ProgramSenderSnapshot(
            senderId = "none",
            currentTrackId = "none",
            expectedTrackId = "none",
            enabled = false,
            readyState = "none",
            lastReplaceAt = "none"
        )
    }
}
     