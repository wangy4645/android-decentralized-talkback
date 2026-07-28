package com.talkback.core.signaling.peer

enum class PeerEdgeSignalingNotReadyReason {
    LOCAL_NOT_BIDIRECTIONAL,
    GENERATION_MISMATCH,
    FRESHNESS_EXPIRED,
    NEVER_OBSERVED,
    GENERATION_INVALIDATED
}