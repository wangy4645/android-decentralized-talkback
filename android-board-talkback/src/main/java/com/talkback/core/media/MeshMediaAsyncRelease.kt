package com.talkback.core.media

/**
 * Offloads mesh PeerConnection factory release from talkback-coordinator.
 * [releaseAction] runs on the media edge executor; [onReleased] must run back on coordinator.
 */
fun interface MeshMediaAsyncRelease {
    fun release(
        moduleId: String,
        sessionId: String?,
        origin: String,
        releaseAction: () -> Unit,
        onReleased: (success: Boolean) -> Unit
    )
}
