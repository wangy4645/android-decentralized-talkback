package com.talkback.core.endpointtext

import com.talkback.core.model.EndpointAddress

/**
 * Inbound Endpoint Text delivered to the app layer via runtime callback.
 * Transient — not persisted, not a Session.
 */
data class EndpointTextEvent(
    val messageId: String,
    val from: EndpointAddress,
    val to: EndpointAddress,
    val text: String,
    val priority: String = "INLINE",
    val sessionHint: String? = null
)
