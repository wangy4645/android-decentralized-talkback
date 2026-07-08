package com.talkback.core.media

import com.talkback.core.webrtc.MediaBearerScope

data class MediaSessionState(
    val moduleId: String,
    val scope: MediaBearerScope,
    val lifecycle: MediaLifecycle,
    val iceState: String,
    val generation: Long
)

data class MediaBarrierResult(
    val moduleCount: Int,
    val unresolvedModuleIds: List<String>
) {
    val unresolvedCount: Int get() = unresolvedModuleIds.size
}
