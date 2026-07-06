package com.talkback.governance.capability

/**
 * Stable capability vocabulary for admission control (ADR-0015).
 * Operations depend on capabilities, not on runtime implementation types.
 */
sealed interface Capability {
    data object Membership : Capability
    data object Routing : Capability
    data object Authority : Capability
    data object Conference : Capability
    data object Media : Capability
    data object Directory : Capability
}
