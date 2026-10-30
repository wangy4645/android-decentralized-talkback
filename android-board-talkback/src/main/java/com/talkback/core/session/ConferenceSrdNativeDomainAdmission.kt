package com.talkback.core.session

import java.util.concurrent.TimeUnit

/**
 * B2-1 Commit 2: lease admission before conference SRD native path (IA-001 AUTH-1/2).
 * Non-blocking: BUSY / QUARANTINED return without invoking [block].
 * G2-RCA2: [runWithLeaseBlocking] waits for holder release before second-edge SRD.
 */
object ConferenceSrdNativeDomainAdmission {

    private const val DEFAULT_LEASE_WAIT_MS = 30_000L
    private const val LEASE_POLL_MS = 5L

    sealed class Outcome<out T> {
        data class Completed<T>(val value: T) : Outcome<T>()
        data class Busy(val holderEdgeKey: String) : Outcome<Nothing>()
        data object Quarantined : Outcome<Nothing>()
    }

    fun <T> runWithLease(
        domain: ConferenceNativeExecutionDomain,
        edgeKey: String,
        logLine: (String) -> Unit = {},
        block: () -> T,
    ): Outcome<T> =
        when (val request = domain.requestLease(edgeKey)) {
            is ConferenceNativeExecutionDomain.RequestResult.Busy -> {
                ConferenceNativeDomainIdleObservability.logHolderSnapshot(
                    domain = domain,
                    trigger = "LEASE_BUSY",
                    contextEdgeKey = edgeKey,
                    logLine = logLine,
                )
                Outcome.Busy(request.holderEdgeKey)
            }
            ConferenceNativeExecutionDomain.RequestResult.Quarantined -> {
                ConferenceNativeDomainIdleObservability.logHolderSnapshot(
                    domain = domain,
                    trigger = "LEASE_QUARANTINED",
                    contextEdgeKey = edgeKey,
                    logLine = logLine,
                )
                Outcome.Quarantined
            }
            ConferenceNativeExecutionDomain.RequestResult.Granted -> {
                ConferenceNativeDomainIdleObservability.logHolderSnapshot(
                    domain = domain,
                    trigger = "LEASE_GRANTED",
                    contextEdgeKey = edgeKey,
                    logLine = logLine,
                )
                try {
                    Outcome.Completed(block())
                } finally {
                    val priorSnapshot = domain.currentSnapshot()
                    val released = domain.releaseLease(
                        edgeKey,
                        ConferenceNativeExecutionDomain.ReleaseReason.NORMAL,
                    )
                    ConferenceNativeDomainIdleObservability.logLeaseRelease(
                        edgeKey = edgeKey,
                        reason = ConferenceNativeExecutionDomain.ReleaseReason.NORMAL,
                        released = released,
                        priorSnapshot = priorSnapshot,
                        logLine = logLine,
                    )
                    ConferenceNativeDomainIdleObservability.logHolderSnapshot(
                        domain = domain,
                        trigger = "LEASE_RELEASE",
                        contextEdgeKey = edgeKey,
                        logLine = logLine,
                    )
                    ConferenceNativeDomainIdleObservability.tryEmitDomainIdleE3(
                        edgeKey = edgeKey,
                        domain = domain,
                        leaseReleased = released,
                        logLine = logLine,
                    )
                }
            }
        }

    /**
     * Deterministic second-edge admission: block on [edgeKey]'s executor until the domain
     * slot is free or [maxWaitMs] elapses (returns [Outcome.Busy] without invoking [block]).
     */
    fun <T> runWithLeaseBlocking(
        domain: ConferenceNativeExecutionDomain,
        edgeKey: String,
        maxWaitMs: Long = DEFAULT_LEASE_WAIT_MS,
        pollMs: Long = LEASE_POLL_MS,
        logLine: (String) -> Unit = {},
        block: () -> T,
    ): Outcome<T> {
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs)
        while (true) {
            when (val outcome = runWithLease(domain, edgeKey, logLine, block)) {
                is Outcome.Completed -> return outcome
                Outcome.Quarantined -> return outcome
                is Outcome.Busy -> {
                    if (System.nanoTime() >= deadlineNs) {
                        return outcome
                    }
                    Thread.sleep(pollMs)
                }
            }
        }
    }
}
