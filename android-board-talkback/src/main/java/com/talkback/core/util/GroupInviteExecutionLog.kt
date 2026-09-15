package com.talkback.core.util

object GroupInviteExecutionLog {
    const val DEFERRED_REASON = "DEFERRED_ENGINE_UNAVAILABLE"

    fun deferredEngineUnavailable(
        channelId: String,
        peerModuleId: String,
        sessionId: String,
        releaseInFlight: Boolean,
    ): String =
        "GIE_DEFERRED_ENGINE_UNAVAILABLE ch=$channelId peer=$peerModuleId " +
            "session=$sessionId releaseInFlight=$releaseInFlight"

    fun executionOpportunity(
        channelId: String,
        peerModuleId: String,
        sessionId: String,
        origin: String,
    ): String =
        "GIE_EXECUTION_OPPORTUNITY ch=$channelId peer=$peerModuleId " +
            "session=$sessionId origin=$origin"

    fun wireSent(
        channelId: String,
        peerModuleId: String,
        sessionId: String,
    ): String =
        "GIE_GROUP_INVITE_WIRE_SENT ch=$channelId peer=$peerModuleId session=$sessionId"

    fun provisionSkippedDuplicate(
        channelId: String,
        peerModuleId: String,
        sessionId: String,
        gate: String,
    ): String =
        "GIE_PROVISION_SKIPPED_DUPLICATE ch=$channelId peer=$peerModuleId " +
            "session=$sessionId gate=$gate"

    fun provisionFailed(
        channelId: String,
        peerModuleId: String,
        sessionId: String,
    ): String =
        "GIE_PROVISION_FAILED ch=$channelId peer=$peerModuleId session=$sessionId " +
            "intent=RETAINED"
}
