package com.talkback.core.presence

import com.talkback.core.session.SessionDisposition
import com.talkback.core.session.SessionType

/**
 * Per-Session read-only projection of protocol state (ADR-0003).
 * Floor fields omitted for sessions that do not use floor control.
 */
data class SessionPresenceSnapshot(
    val sessionId: String,
    val type: SessionType,
    val protocolFloorOwnerKey: String?,
    val membershipKeys: List<String>,
    val rosterEpoch: Long,
    val disposition: SessionDisposition
)
