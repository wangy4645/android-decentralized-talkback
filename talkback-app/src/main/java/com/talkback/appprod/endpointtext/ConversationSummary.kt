package com.talkback.appprod.endpointtext

/**
 * One row in the process-local conversation list (presentation cache only).
 */
data class ConversationSummary(
    val endpointKey: String,
    val lastMessage: String,
    val lastTimestampMs: Long,
    val lastDirection: EndpointTextDirection,
    val unreadCount: Int
)
