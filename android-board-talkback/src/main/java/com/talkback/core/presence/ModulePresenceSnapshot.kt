package com.talkback.core.presence

/**
 * Per-Module read-only projection of local execution state (ADR-0003).
 */
data class ModulePresenceSnapshot(
    val localUplinkGrant: Boolean,
    val activeCaptureEndpointKey: String?,
    val iceByPeer: Map<String, String>,
    /** Populated when Foreground Activity stack lands (#6); null until then. */
    val stackTopSessionId: String? = null
)
