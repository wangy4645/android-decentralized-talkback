package com.talkback.core.session

/**
 * DIO-001: read-only native domain idle / lease facts (observation only).
 * Queries [ConferenceNativeExecutionDomain] slot state and per-edge SRD attempt flags —
 * does not infer holder from log ordering.
 */
object ConferenceNativeDomainIdleObservability {

    data class DomainHolderFact(
        val holderEdgeKey: String,
        val domainId: String,
        val occupancyState: String,
        val executionState: String,
    )

    fun queryHolderFact(domain: ConferenceNativeExecutionDomain): DomainHolderFact {
        val snapshot = domain.currentSnapshot()
        val holderKey = snapshot?.edgeKey
        val occupancy = when (snapshot?.state) {
            null -> "NONE"
            ConferenceNativeExecutionDomain.LeaseState.ACTIVE -> "ACTIVE"
            ConferenceNativeExecutionDomain.LeaseState.STUCK -> "STUCK"
            ConferenceNativeExecutionDomain.LeaseState.QUARANTINED -> "QUARANTINED"
        }
        return DomainHolderFact(
            holderEdgeKey = holderKey ?: "NONE",
            domainId = ConferenceSrdNativeDomainObservability.DOMAIN_ID,
            occupancyState = occupancy,
            executionState = resolveExecutionState(holderKey),
        )
    }

    fun formatHolderSnapshot(
        fact: DomainHolderFact,
        trigger: String,
        contextEdgeKey: String? = null,
    ): String = buildString {
        append("NATIVE_DOMAIN_HOLDER_SNAPSHOT")
        append(" trigger=").append(trigger)
        contextEdgeKey?.let { append(" contextEdgeKey=").append(it) }
        append(" holderEdgeKey=").append(fact.holderEdgeKey)
        append(" domainId=").append(fact.domainId)
        append(" occupancyState=").append(fact.occupancyState)
        append(" executionState=").append(fact.executionState)
    }

    fun formatLeaseRelease(
        edgeKey: String,
        reason: ConferenceNativeExecutionDomain.ReleaseReason,
        released: Boolean,
        priorHolderEdgeKey: String?,
        priorLeaseState: ConferenceNativeExecutionDomain.LeaseState?,
    ): String = buildString {
        append("NATIVE_DOMAIN_LEASE_RELEASE")
        append(" edgeKey=").append(edgeKey)
        append(" reason=").append(reason.name)
        append(" released=").append(released)
        append(" priorHolderEdgeKey=").append(priorHolderEdgeKey ?: "NONE")
        append(" priorLeaseState=").append(priorLeaseState?.name ?: "NONE")
    }

    fun formatDomainIdleE3(edgeKey: String): String =
        "DOMAIN_IDLE_E3 edgeKey=$edgeKey domainId=${ConferenceSrdNativeDomainObservability.DOMAIN_ID}"

    fun logHolderSnapshot(
        domain: ConferenceNativeExecutionDomain,
        trigger: String,
        contextEdgeKey: String? = null,
        logLine: (String) -> Unit = {},
    ) {
        logLine(formatHolderSnapshot(queryHolderFact(domain), trigger, contextEdgeKey))
    }

    fun logLeaseRelease(
        edgeKey: String,
        reason: ConferenceNativeExecutionDomain.ReleaseReason,
        released: Boolean,
        priorSnapshot: ConferenceNativeExecutionDomain.HolderSnapshot?,
        logLine: (String) -> Unit = {},
    ) {
        logLine(
            formatLeaseRelease(
                edgeKey = edgeKey,
                reason = reason,
                released = released,
                priorHolderEdgeKey = priorSnapshot?.edgeKey,
                priorLeaseState = priorSnapshot?.state,
            ),
        )
    }

    fun tryEmitDomainIdleE3(
        edgeKey: String,
        domain: ConferenceNativeExecutionDomain,
        leaseReleased: Boolean,
        logLine: (String) -> Unit = {},
    ) {
        if (!leaseReleased) return
        val attempt = ConferenceSrdNativeObservability.peekAttempt(edgeKey) ?: return
        if (!attempt.sawNativeCallExit || !attempt.sawDomainExecutionExit) return
        val fact = queryHolderFact(domain)
        if (fact.holderEdgeKey != "NONE" || fact.occupancyState != "NONE") return
        logLine(formatDomainIdleE3(edgeKey))
    }

    internal fun resolveExecutionState(holderEdgeKey: String?): String {
        if (holderEdgeKey == null) return "NONE"
        val attempt = ConferenceSrdNativeObservability.peekAttempt(holderEdgeKey)
            ?: return "OCCUPIED_NO_ATTEMPT"
        if (attempt.sawDomainExecutionEnter && !attempt.sawDomainExecutionExit) {
            if (attempt.sawNativeCallEnter && !attempt.sawNativeCallExit) {
                return "NATIVE_CALL_ACTIVE"
            }
            return "EXECUTION_ACTIVE"
        }
        if (attempt.sawDomainExecutionExit) {
            val gap = ConferenceSrdNativeObservability.classifyWatchdogGap(attempt)
            if (!attempt.sawExit && gap.state == "POST_NATIVE_CALLBACK_WAIT") {
                return "POST_NATIVE_CALLBACK_WAIT"
            }
            if (attempt.sawExit) {
                return "SRD_COMPLETE"
            }
            return "EXECUTION_EXIT_LEASE_HELD"
        }
        if (attempt.sawDomainLeaseGranted && !attempt.sawDomainExecutionEnter) {
            return "LEASE_GRANTED"
        }
        return "UNKNOWN"
    }
}
