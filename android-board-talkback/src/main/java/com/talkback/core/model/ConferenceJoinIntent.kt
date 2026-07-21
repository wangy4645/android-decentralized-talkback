package com.talkback.core.model

/**
 * Membership / join-plane intent (ADR-0021 addendum).
 *
 * USER_REJOIN is Membership Intent — MUST NOT enter RecoveryController.
 * RECOVERY_REATTACH is Connectivity recovery control-plane — only from Recovery FSM.
 */
enum class ConferenceJoinIntent {
    NORMAL_JOIN,
    USER_REJOIN,
    RECOVERY_REATTACH,
    /** Delivery receipt for [RECOVERY_REATTACH] — connectivity plane only. */
    RECOVERY_REATTACH_RECEIPT;

    fun encode(): String = name

    companion object {
        fun fromPayload(raw: String?): ConferenceJoinIntent =
            values().firstOrNull { it.name == raw } ?: NORMAL_JOIN
    }
}