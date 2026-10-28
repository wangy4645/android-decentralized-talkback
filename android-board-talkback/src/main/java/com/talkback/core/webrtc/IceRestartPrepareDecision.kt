package com.talkback.core.webrtc

/**
 * Whether [RealWebRtcAudioEngine] may legally clear negotiation state before an ICE-restart offer.
 * Rollback is only valid in HAVE_LOCAL_OFFER / HAVE_REMOTE_OFFER. STABLE with a remote description
 * must not be "fixed" by clearing application flags.
 */
internal object IceRestartPrepareDecision {
    enum class Action {
        RESET_FACTS_ONLY,
        ROLLBACK_THEN_RESET,
        ABORT_ROLLBACK_ILLEGAL,
    }

    fun evaluate(
        signalingState: String?,
        remoteDescriptionType: String?,
    ): Action {
        val state = signalingState?.uppercase() ?: return Action.ABORT_ROLLBACK_ILLEGAL
        val remote = remoteDescriptionType?.uppercase()
        return when {
            state == "HAVE_LOCAL_OFFER" || state == "HAVE_REMOTE_OFFER" ->
                Action.ROLLBACK_THEN_RESET
            state == "STABLE" && remote == null ->
                Action.RESET_FACTS_ONLY
            else ->
                Action.ABORT_ROLLBACK_ILLEGAL
        }
    }
}
