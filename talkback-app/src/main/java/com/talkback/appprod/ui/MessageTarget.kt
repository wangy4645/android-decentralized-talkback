package com.talkback.appprod.ui

/**
 * Unified message target — Endpoint DM vs Channel broadcast (ADR-0039 / ADR-0041).
 * Never encode multi-select recipient lists here.
 */
sealed class MessageTarget {
    data class Endpoint(val endpointKey: String) : MessageTarget()
    data class Channel(val channelId: String) : MessageTarget()
}
