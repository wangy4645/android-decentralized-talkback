package com.talkback.core.conference.session

/**
 * Field RCA taxonomy for per-source mix pull failures (observability only).
 * See PROPOSAL-SOURCE-EMPTY-DIAGNOSTICS-20261019.txt.
 */
enum class SourcePullEmptyReason {
    /** Successful real frame pull — not logged. */
    REAL,
    NOT_SELECTED_FOR_MIX_PULL,
    NO_PULL_SLOT,
    DEFERRED,
    MEMBERSHIP_OR_BINDING_MISMATCH,
    QUEUE_EMPTY,
    SLOT_WAITING,
    SLOT_MISMATCH_OR_LATE,
    NOT_DECODE_ELIGIBLE,
    PULL_PLC,
}
