package com.talkback.core.conference.session

/**
 * B8-C — why [PlayoutMixSlotResolver.resolve] returned null (starvation path only).
 * Does not change Top-K / mix eligibility; observability + desk classification.
 */
enum class PlayoutMixResolveSkipReason {
    /** No Top-K members at selection time (VAD / ranking). */
    TOP_K_EMPTY,

    /** Top-K non-empty but no member yielded a pullable Layer-A slot. */
    TOP_K_NO_PULLABLE_SLOT,

    /** Selected Top-K member missing Layer-A playhead projection. */
    TOP_K_LAYER_A_MISSING,

    /** Top-K member jitter empty at resolve. */
    TOP_K_BUFFER_EMPTY,

    /** Buffered frames exist but all sit strictly below [nextExpected] (legacy bucket). */
    TOP_K_BUFFER_BEFORE_CURSOR,

    /** Prefix below cursor; displacement exceeds [MediaJitterConstants.MAX_REORDER_PACKETS]. */
    TOP_K_BUFFER_BEFORE_CURSOR_GAP_EXCEEDS_REORDER,

    /** Prefix below cursor; earliest frame past playout deadline. */
    TOP_K_BUFFER_BEFORE_CURSOR_UNPLAYABLE,

    /** Off-Top-K admitted source(s) still hold buffered frames (not mixed by design). */
    BUFFERED_ONLY_OFF_TOP_K,
}
