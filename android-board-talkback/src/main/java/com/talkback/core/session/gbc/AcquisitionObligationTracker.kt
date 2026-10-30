package com.talkback.core.session.gbc

/**
 * GBC-L0..L5 acquisition obligation lifecycle.
 * Independent of scheduler attempt / waitingForPrimary / retry counts.
 */
class AcquisitionObligationTracker {
    private var obligation: AcquisitionObligation? = null

    fun current(): AcquisitionObligation? = obligation

    fun state(): ObligationState = obligation?.state ?: ObligationState.CLOSED

    fun open(channelId: String, reason: String) {
        obligation =
            AcquisitionObligation(
                channelId = channelId,
                state = ObligationState.OPEN,
                reason = reason,
            )
    }

    fun closeIfOpen() {
        val cur = obligation ?: return
        if (cur.state == ObligationState.OPEN) {
            obligation = cur.copy(state = ObligationState.CLOSED)
        }
    }

    fun isOpen(): Boolean = state() == ObligationState.OPEN

    /**
     * INSUFFICIENT / attempt terminal MUST NOT close (L2/L5).
     * No-op by design — exposed for harness clarity.
     */
    fun onInsufficientOrAttemptTerminal() {
        // obligation remains OPEN if already OPEN
    }
}
