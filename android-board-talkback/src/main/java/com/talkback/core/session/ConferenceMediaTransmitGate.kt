package com.talkback.core.session

/**
 * ADR-0026 edge-scoped transmit barrier.
 *
 * Local conference capture is gated by local publish preconditions only.
 * Remote edge recovery / obligation facts are diagnostic (see [ConferenceBarrierDiagnostics]);
 * they MUST NOT block unrelated healthy publish paths.
 */
object ConferenceMediaTransmitGate {

    data class Input(
        val localConferenceActive: Boolean,
        val localMuted: Boolean,
        val localPublisherReady: Boolean
    )

    fun canPublishConferenceAudio(input: Input): Boolean {
        if (!input.localConferenceActive) return false
        if (input.localMuted) return false
        if (!input.localPublisherReady) return false
        return true
    }
}
