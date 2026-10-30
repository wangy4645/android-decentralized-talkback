package com.talkback.core.util

object GroupInviteInboundExecutionLog {
    fun deferredEngineUnavailable(
        channelId: String,
        peerModuleId: String,
        sessionId: String,
        releaseInFlight: Boolean,
        acceptPath: String,
    ): String =
        "IGIE_DEFERRED_ENGINE_UNAVAILABLE ch=$channelId peer=$peerModuleId " +
            "session=$sessionId path=$acceptPath releaseInFlight=$releaseInFlight"

    fun executionOpportunity(
        channelId: String,
        peerModuleId: String,
        sessionId: String,
        origin: String,
        acceptPath: String,
    ): String =
        "IGIE_EXECUTION_OPPORTUNITY ch=$channelId peer=$peerModuleId " +
            "session=$sessionId path=$acceptPath origin=$origin"

    fun acceptWireSent(
        channelId: String,
        peerModuleId: String,
        sessionId: String,
        acceptPath: String,
    ): String =
        "IGIE_GROUP_ACCEPT_WIRE_SENT ch=$channelId peer=$peerModuleId " +
            "session=$sessionId path=$acceptPath"

    fun provisionSkippedDuplicate(
        channelId: String,
        peerModuleId: String,
        sessionId: String,
        gate: String,
    ): String =
        "IGIE_PROVISION_SKIPPED_DUPLICATE ch=$channelId peer=$peerModuleId " +
            "session=$sessionId gate=$gate"

    fun provisionFailed(
        channelId: String,
        peerModuleId: String,
        sessionId: String,
    ): String =
        "IGIE_PROVISION_FAILED ch=$channelId peer=$peerModuleId session=$sessionId " +
            "obligation=RETAINED"
}
