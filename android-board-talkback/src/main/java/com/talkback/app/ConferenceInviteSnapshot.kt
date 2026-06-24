package com.talkback.app

data class ConferenceInviteSnapshot(
    val sessionId: String,
    val channelId: String,
    val hostKey: String,
    val hostModuleId: String,
    val memberCount: Int,
    val receivedAtMs: Long
)
