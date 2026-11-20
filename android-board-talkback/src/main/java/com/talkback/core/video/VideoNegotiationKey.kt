package com.talkback.core.video

/**
 * Isolates one viewer PeerConnection under a live stream.
 * Stale generation or negotiationId must drop Answer/ICE.
 */
data class VideoNegotiationKey(
    val sessionId: String,
    val streamId: String,
    val videoGeneration: Long,
    val negotiationId: String,
) {
    fun matches(
        sessionId: String,
        streamId: String,
        videoGeneration: Long,
        negotiationId: String,
    ): Boolean =
        this.sessionId == sessionId &&
            this.streamId == streamId &&
            this.videoGeneration == videoGeneration &&
            this.negotiationId == negotiationId
}
