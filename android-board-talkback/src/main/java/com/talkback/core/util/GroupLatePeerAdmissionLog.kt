package com.talkback.core.util

/** Late-peer admission observability (grep: GROUP_LATE_PEER_*). */
object GroupLatePeerAdmissionLog {

    fun discoveredMessage(
        peerModuleId: String,
        primaryModuleId: String,
        channelId: String,
        sessionId: String,
        channelSource: String
    ): String =
        "GROUP_LATE_PEER_DISCOVERED peer=$peerModuleId primary=$primaryModuleId " +
            "ch=$channelId session=$sessionId channelSource=$channelSource"

    fun skippedMessage(
        peerModuleId: String,
        channelId: String,
        reason: String,
        sessionId: String?
    ): String =
        "GROUP_LATE_PEER_ADMISSION_SKIPPED peer=$peerModuleId ch=$channelId " +
            "reason=$reason session=${sessionId ?: "none"}"
}
