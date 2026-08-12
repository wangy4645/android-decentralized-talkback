package com.talkback.core.util

/**
 * #179 bootstrap admission observability (grep: GROUP_BOOTSTRAP_INTENT_*).
 * Message format only; coordinator [log] is the emission point.
 */
object GroupBootstrapAdmissionLog {

    fun intentCreatedMessage(channelId: String, peerModuleId: String, reason: String): String =
        "GROUP_BOOTSTRAP_INTENT_CREATED ch=$channelId peer=$peerModuleId reason=$reason"

    fun intentWaitingMessage(channelId: String, peerModuleId: String, reason: String): String =
        "GROUP_BOOTSTRAP_INTENT_WAITING ch=$channelId peer=$peerModuleId reason=$reason"

    fun inviteIssuedMessage(channelId: String, peerModuleId: String, sessionId: String): String =
        "GROUP_BOOTSTRAP_INVITE_ISSUED ch=$channelId peer=$peerModuleId sessionId=$sessionId"

    fun fallbackSuppressedMessage(channelId: String, peerModuleId: String, reason: String): String =
        "GROUP_BOOTSTRAP_FALLBACK_SUPPRESSED ch=$channelId peer=$peerModuleId reason=$reason"
}
