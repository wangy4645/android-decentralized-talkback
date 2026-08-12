package com.talkback.core.session

import com.talkback.core.model.EndpointAddress

/**
 * ADR-0053 E4 — session-scoped admission history for formerly-admitted peers.
 * Not part of canonical roster, authority snapshot, or digest/hash.
 */
data class FormerAdmittedPeer(
    val endpoint: EndpointAddress,
    val prunedAtEpoch: Long,
    val recordedAtMs: Long = System.currentTimeMillis()
)
