package com.talkback.core.session.gbc

/**
 * Coordinator-owned acquisition wire scheduling outcome (ACQ send-on-locate repair).
 *
 * Authority remains GBC obligation OPEN; HELLO only updates holder availability.
 */
sealed class AcquisitionWirePlan {
    data class Send(
        val channelId: String,
        val correlation: String,
        val holderModuleIds: List<String>,
    ) : AcquisitionWirePlan()

    /** RequestFact or locate opportunity with zero eligible holders; obligation stays OPEN. */
    data class DeferredNoHolder(
        val channelId: String,
        val correlation: String,
    ) : AcquisitionWirePlan()

    /** Obligation CLOSED — no wire attempt permitted. */
    data class ObligationClosed(
        val channelId: String,
    ) : AcquisitionWirePlan()

    /** Duplicate HELLO for an already-located holder — no new opportunity. */
    data object NoNewHolderLocate : AcquisitionWirePlan()
}
