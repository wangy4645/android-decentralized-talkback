package com.talkback.core.model

/**
 * ADR-0035 PR4 — terminal handler outcome on RECOVERY_REATTACH_ACK.
 */
enum class RecoveryHandlerOutcome {
    ACCEPTED,
    ALREADY_SATISFIED;

    fun isTerminal(): Boolean = this == ACCEPTED || this == ALREADY_SATISFIED

    companion object {
        fun fromWire(raw: String?): RecoveryHandlerOutcome? = when {
            raw.isNullOrBlank() -> null
            raw.equals(ACCEPTED.name, ignoreCase = true) -> ACCEPTED
            raw.equals(ALREADY_SATISFIED.name, ignoreCase = true) -> ALREADY_SATISFIED
            else -> null
        }
    }
}