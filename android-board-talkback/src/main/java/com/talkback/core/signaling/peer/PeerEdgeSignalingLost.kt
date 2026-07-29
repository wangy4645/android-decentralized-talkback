package com.talkback.core.signaling.peer

/** Peer-edge signaling qualification loss observation (ADR-0022 Q6). Hint is not repair. */
data class PeerEdgeSignalingLost(
    val remoteModuleId: String,
    val generation: Long,
    val reason: PeerEdgeSignalingNotReadyReason,
    val lostAtMs: Long
)