package com.talkback.appprod.ui

/**
 * Inbound Endpoint Text for INLINE UI toast. Transient — not stored.
 */
data class EndpointTextUiEvent(
    val fromKey: String,
    val fromLabel: String,
    val text: String
)
