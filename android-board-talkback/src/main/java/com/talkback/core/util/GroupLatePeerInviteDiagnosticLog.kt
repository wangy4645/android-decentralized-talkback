package com.talkback.core.util

/** Late-peer invite path diagnostics (grep: LATE_PEER_INVITE_DIAG). */
object GroupLatePeerInviteDiagnosticLog {

    fun pairwiseReinviteSkipped(
        peerModuleId: String,
        reason: String,
        sessionId: String? = null,
        channelId: String? = null,
        detail: String? = null,
    ): String = buildLine("PAIRWISE_REINVITE", peerModuleId, reason, sessionId, channelId, detail)

    fun ensureCanonicalSkipped(
        peerModuleId: String,
        reason: String,
        sessionId: String? = null,
        channelId: String? = null,
        detail: String? = null,
    ): String = buildLine("ENSURE_CANONICAL", peerModuleId, reason, sessionId, channelId, detail)

    fun pairwiseReinviteOutcome(
        peerModuleId: String,
        sessionId: String,
        channelId: String?,
        dispatched: Int,
        phase: String? = null,
        gate: String? = null,
    ): String =
        "LATE_PEER_INVITE_DIAG path=PAIRWISE_REINVITE peer=$peerModuleId session=$sessionId " +
            "ch=${channelId ?: "none"} reason=DISPATCH_RESULT dispatched=$dispatched " +
            "phase=${phase ?: "n/a"} gate=${gate ?: "n/a"}"

    fun ensureCanonicalOutcome(
        peerModuleId: String,
        sessionId: String,
        channelId: String,
        meshInvitesSent: Int,
        snapshotFallback: Boolean,
    ): String =
        "LATE_PEER_INVITE_DIAG path=ENSURE_CANONICAL peer=$peerModuleId session=$sessionId ch=$channelId " +
            "reason=INVITE_ATTEMPT meshInvitesSent=$meshInvitesSent snapshotFallback=$snapshotFallback"

    private fun buildLine(
        path: String,
        peerModuleId: String,
        reason: String,
        sessionId: String?,
        channelId: String?,
        detail: String?,
    ): String {
        val detailPart = detail?.let { " detail=$it" } ?: ""
        return "LATE_PEER_INVITE_DIAG path=$path peer=$peerModuleId session=${sessionId ?: "none"} " +
            "ch=${channelId ?: "none"} reason=$reason$detailPart"
    }
}
