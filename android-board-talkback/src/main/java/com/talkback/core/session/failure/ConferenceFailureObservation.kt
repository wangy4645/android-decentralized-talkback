package com.talkback.core.session.failure

import com.talkback.core.session.ConferenceSrdNativeDomainObservability

/**
 * Runtime observations consumed by [ConferenceFailureClassifier].
 *
 * B2-1 inputs (`edgeLocalFailure`, lease holder/waiter keys) are facts — not L1/L2 terminals.
 * Symptoms (`hangingObserved`, `srdTimeoutObserved`) require classifier evaluation before terminals.
 */
data class ConferenceFailureObservation(
    val edgeKey: String,
    val runtimeDomainRef: String = ConferenceSrdNativeDomainObservability.DOMAIN_ID,
    val generationScope: ConferenceFailureGenerationScope,
    /** B2-1 EDGE_LOCAL_FAILURE path — input only. */
    val edgeLocalFailureObserved: Boolean = false,
    /** Holder when this edge holds the native domain lease. */
    val activeLeaseHolderEdgeKey: String? = null,
    /**
     * When this edge is blocked waiting for lease, the current holder edge key.
     * Maps from B2-1 busy path — not equivalent to DOMAIN_BLOCKED.
     */
    val leaseWaitHolderEdgeKey: String? = null,
    val nativeDomainObstructed: Boolean = false,
    val hangingObserved: Boolean = false,
    val srdTimeoutObserved: Boolean = false,
    val negotiationStuckObserved: Boolean = false,
)
