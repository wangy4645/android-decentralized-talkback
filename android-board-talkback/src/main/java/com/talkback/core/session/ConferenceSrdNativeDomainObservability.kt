package com.talkback.core.session

/**
 * B2-1 Commit 3: native domain lease + execution boundary facts (IA-001 PO-3-C).
 * Behavior-neutral — logging and attempt-state correlation only.
 */
object ConferenceSrdNativeDomainObservability {

    const val DOMAIN_ID = "shared-factory"

    fun formatLeaseRequest(ctx: ConferenceSrdObservability.Context): String =
        formatLease("NATIVE_DOMAIN_LEASE_REQUEST", ctx)

    fun formatLeaseGranted(ctx: ConferenceSrdObservability.Context): String =
        formatLease("NATIVE_DOMAIN_LEASE_GRANTED", ctx)

    fun formatLeaseBusy(ctx: ConferenceSrdObservability.Context, holderEdgeKey: String): String =
        formatLease("NATIVE_DOMAIN_LEASE_BUSY", ctx, holderEdgeKey = holderEdgeKey)

    fun formatLeaseQuarantined(ctx: ConferenceSrdObservability.Context): String =
        formatLease("NATIVE_DOMAIN_QUARANTINED", ctx)

    fun formatDomainExecutionEnter(ctx: ConferenceSrdObservability.Context, pcHash: Int?): String =
        formatDomainExecution("NATIVE_DOMAIN_EXECUTION_ENTER", ctx, pcHash)

    fun formatDomainExecutionExit(
        ctx: ConferenceSrdObservability.Context,
        pcHash: Int?,
        elapsedMs: Long,
    ): String = formatDomainExecution("NATIVE_DOMAIN_EXECUTION_EXIT", ctx, pcHash, elapsedMs)

    fun recordLeaseRequest(edgeKey: String) {
        ConferenceSrdNativeObservability.record(edgeKey, "NATIVE_DOMAIN_LEASE_REQUEST")
    }

    fun recordLeaseGranted(edgeKey: String) {
        ConferenceSrdNativeObservability.record(edgeKey, "NATIVE_DOMAIN_LEASE_GRANTED")
    }

    fun recordLeaseBusy(waiterEdgeKey: String, holderEdgeKey: String) {
        ConferenceSrdNativeObservability.recordLeaseBusy(waiterEdgeKey, holderEdgeKey)
    }

    fun recordLeaseQuarantined(edgeKey: String) {
        ConferenceSrdNativeObservability.record(edgeKey, "NATIVE_DOMAIN_QUARANTINED")
    }

    fun recordDomainExecutionEnter(edgeKey: String) {
        ConferenceSrdNativeObservability.record(edgeKey, "NATIVE_DOMAIN_EXECUTION_ENTER")
    }

    fun recordDomainExecutionExit(edgeKey: String, elapsedMs: Long) {
        ConferenceSrdNativeObservability.recordDomainExecutionExit(edgeKey, elapsedMs)
    }

    private fun formatLease(
        event: String,
        ctx: ConferenceSrdObservability.Context,
        holderEdgeKey: String? = null,
    ): String = buildString {
        append(event)
        append(" session=").append(ctx.sessionId)
        append(" edgeKey=").append(ctx.edgeKey)
        append(" remote=").append(ctx.remoteModuleId)
        append(" pcGeneration=").append(ctx.pcGeneration)
        ctx.conferenceGeneration?.let { append(" conferenceGeneration=").append(it) }
        append(" domainId=").append(DOMAIN_ID)
        holderEdgeKey?.let { append(" holderEdgeKey=").append(it) }
    }

    private fun formatDomainExecution(
        event: String,
        ctx: ConferenceSrdObservability.Context,
        pcHash: Int?,
        elapsedMs: Long? = null,
    ): String {
        val thread = Thread.currentThread()
        return buildString {
            append(event)
            append(" session=").append(ctx.sessionId)
            append(" edgeKey=").append(ctx.edgeKey)
            append(" remote=").append(ctx.remoteModuleId)
            append(" pcGeneration=").append(ctx.pcGeneration)
            ctx.conferenceGeneration?.let { append(" conferenceGeneration=").append(it) }
            append(" domainId=").append(DOMAIN_ID)
            pcHash?.let { append(" pcHash=").append(it) }
            append(" thread=").append(thread.name)
            append(" tid=").append(thread.id)
            elapsedMs?.let { append(" elapsedMs=").append(it) }
        }
    }
}
