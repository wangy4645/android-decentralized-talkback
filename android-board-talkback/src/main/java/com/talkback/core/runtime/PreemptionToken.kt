package com.talkback.core.runtime

/**
 * Records who suspended a session so only the preempting foreground activity may resume it (R3).
 */
data class PreemptionToken(
    val suspendedSessionId: String,
    val preemptedBySessionId: String,
    val preemptReason: PreemptReason,
    val actingEndpointId: String
)
