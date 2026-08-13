package com.talkback.core.util

/** #180 pairwise mesh admission activation observability. */
object PairwiseMeshAdmissionLog {

    fun activationEvaluatedMessage(channelId: String, peerModuleId: String, decision: String): String =
        "PAIRWISE_MESH_ADMISSION_ACTIVATION_EVALUATED ch=$channelId peer=$peerModuleId decision=$decision"

    fun activationDeferredMessage(channelId: String, peerModuleId: String, reason: String): String =
        "PAIRWISE_MESH_ADMISSION_ACTIVATION_DEFERRED ch=$channelId peer=$peerModuleId reason=$reason"

    fun inviteIssuedMessage(channelId: String, peerModuleId: String, sessionId: String): String =
        "PAIRWISE_MESH_ADMISSION_INVITE_ISSUED ch=$channelId peer=$peerModuleId sessionId=$sessionId"
}
