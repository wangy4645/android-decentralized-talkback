package com.talkback.core.runtime

/**
 * One frame on the per-Module foreground Activity stack (ADR-0001 R1).
 * Holds [sessionId] only — never a live session object reference.
 */
data class ActivityFrame(
    val activityType: ActivityType,
    val sessionId: String?,
    val actingEndpointId: String,
    val requestedBy: String,
    val preemptReason: PreemptReason,
    val autoResume: Boolean = true
)
