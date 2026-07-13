package com.talkback.core.session

/**
 * Conference presence read model (ADR-0022 R27′).
 * UI MUST consume this for joined/connected/recovering counts — not [ReachabilitySnapshot].
 */
data class ConferencePresenceProjection(
    /** Membership-joined participants (includes local; excludes pending invitees). */
    val joinedCount: Int,
    /** Participants with active mesh connectivity (includes local when session accepted). */
    val connectedCount: Int,
    /** Remote module ids with an active edge recovery obligation on this device. */
    val recoveringPeers: Set<String> = emptySet(),
    /**
     * Advisory media-health facts (ADR-0023 R29-C). MUST NOT drive joined/left roster semantics.
     */
    val mediaUnavailablePeers: Set<String> = emptySet()
)
