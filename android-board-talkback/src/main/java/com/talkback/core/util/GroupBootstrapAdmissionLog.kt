package com.talkback.core.util

/**
 * #179 bootstrap admission observability (grep: GROUP_BOOTSTRAP_INTENT_*).
 * Read-only telemetry; does not gate signaling.
 */
object GroupBootstrapAdmissionLog {

    fun intentCreated(channelId: String, peerModuleId: String, reason: String) {
        log("GROUP_BOOTSTRAP_INTENT_CREATED ch=$channelId peer=$peerModuleId reason=$reason")
    }

    fun intentWaiting(channelId: String, peerModuleId: String, reason: String) {
        log("GROUP_BOOTSTRAP_INTENT_WAITING ch=$channelId peer=$peerModuleId reason=$reason")
    }

    fun inviteIssued(channelId: String, peerModuleId: String, sessionId: String) {
        log(
            "GROUP_BOOTSTRAP_INVITE_ISSUED ch=$channelId peer=$peerModuleId sessionId=$sessionId"
        )
    }

    private fun log(message: String) {
        TalkbackLog.i(message)
    }
}
