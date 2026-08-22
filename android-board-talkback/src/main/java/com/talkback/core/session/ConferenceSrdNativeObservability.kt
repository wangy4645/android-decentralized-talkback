package com.talkback.core.session

import java.util.concurrent.ConcurrentHashMap

/**
 * RCA-OBS-001: read-only native SRD completion trace.
 * Tracks per-edge lifecycle segments for [SRD_WATCHDOG_GAP] adjudication.
 */
object ConferenceSrdNativeObservability {

    internal data class AttemptState(
        val ctx: ConferenceSrdObservability.Context,
        val conferenceGeneration: Long?,
        val answerSdpBytes: Int,
        val answerSdpLines: Int,
        @Volatile var lastEvent: String = "NONE",
        @Volatile var sawJniReturn: Boolean = false,
        @Volatile var sawCallback: Boolean = false,
        @Volatile var sawMutexReleased: Boolean = false,
        @Volatile var sawExit: Boolean = false,
        @Volatile var sawNativeCallEnter: Boolean = false,
        @Volatile var sawNativeCallExit: Boolean = false,
        @Volatile var nativeCallElapsedMs: Long? = null,
        @Volatile var sawDomainLeaseRequest: Boolean = false,
        @Volatile var sawDomainLeaseGranted: Boolean = false,
        @Volatile var sawDomainLeaseBusy: Boolean = false,
        @Volatile var sawDomainLeaseQuarantined: Boolean = false,
        @Volatile var sawDomainExecutionEnter: Boolean = false,
        @Volatile var sawDomainExecutionExit: Boolean = false,
        @Volatile var domainHolderEdgeKey: String? = null,
        @Volatile var domainExecutionElapsedMs: Long? = null,
    )

    private val attempts = ConcurrentHashMap<String, AttemptState>()

    fun beginAttempt(
        ctx: ConferenceSrdObservability.Context,
        conferenceGeneration: Long?,
        answerSdp: String
    ) {
        attempts[ctx.edgeKey] = AttemptState(
            ctx = ctx,
            conferenceGeneration = conferenceGeneration,
            answerSdpBytes = answerSdp.length,
            answerSdpLines = answerSdp.lineSequence().count()
        )
    }

    fun endAttempt(edgeKey: String) {
        attempts.remove(edgeKey)
    }

    fun edgeKeyFromDiagnosticTag(tag: String): String? {
        val parts = tag.split("|")
        return when {
            parts.size >= 2 -> ConferenceMediaJniAffinity.edgeKey(parts[0], parts[1])
            else -> null
        }
    }

    fun record(edgeKey: String, event: String) {
        val state = attempts[edgeKey] ?: return
        state.lastEvent = event
        when (event) {
            "NATIVE_DOMAIN_LEASE_REQUEST" -> state.sawDomainLeaseRequest = true
            "NATIVE_DOMAIN_LEASE_GRANTED" -> state.sawDomainLeaseGranted = true
            "NATIVE_DOMAIN_LEASE_BUSY" -> state.sawDomainLeaseBusy = true
            "NATIVE_DOMAIN_QUARANTINED" -> state.sawDomainLeaseQuarantined = true
            "NATIVE_DOMAIN_EXECUTION_ENTER" -> state.sawDomainExecutionEnter = true
            "NATIVE_DOMAIN_EXECUTION_EXIT" -> state.sawDomainExecutionExit = true
            "SRD_NATIVE_CALL_ENTER" -> state.sawNativeCallEnter = true
            "SRD_NATIVE_CALL_EXIT" -> state.sawNativeCallExit = true
            "SRD_JNI_RETURN" -> state.sawJniReturn = true
            "SRD_CALLBACK_ENTER", "SRD_CALLBACK_FAILURE" -> state.sawCallback = true
            "SRD_MUTEX_RELEASED" -> state.sawMutexReleased = true
            "SRD_EXIT" -> state.sawExit = true
        }
    }

    fun recordLeaseBusy(waiterEdgeKey: String, holderEdgeKey: String) {
        val state = attempts[waiterEdgeKey] ?: return
        state.lastEvent = "NATIVE_DOMAIN_LEASE_BUSY"
        state.sawDomainLeaseBusy = true
        state.domainHolderEdgeKey = holderEdgeKey
    }

    fun recordDomainExecutionExit(edgeKey: String, elapsedMs: Long) {
        val state = attempts[edgeKey] ?: return
        state.sawDomainExecutionExit = true
        state.domainExecutionElapsedMs = elapsedMs
        state.lastEvent = "NATIVE_DOMAIN_EXECUTION_EXIT"
    }

    fun recordNativeCallExit(edgeKey: String, elapsedMs: Long) {
        val state = attempts[edgeKey] ?: return
        state.sawNativeCallExit = true
        state.nativeCallElapsedMs = elapsedMs
        state.lastEvent = "SRD_NATIVE_CALL_EXIT"
    }

    fun recordFromTag(tag: String, event: String) {
        val edgeKey = edgeKeyFromDiagnosticTag(tag) ?: return
        record(edgeKey, event)
    }

    fun correlationFields(edgeKey: String): String {
        val state = attempts[edgeKey] ?: return "edgeKey=$edgeKey"
        val ctx = state.ctx
        val conferenceGen = state.conferenceGeneration?.let { " conferenceGeneration=$it" } ?: ""
        return buildString {
            append("session=").append(ctx.sessionId)
            append(" edgeKey=").append(edgeKey)
            append(" edge=").append(ctx.edgeLabel)
            append(" remote=").append(ctx.remoteModuleId)
            append(" pcGeneration=").append(ctx.pcGeneration)
            append(conferenceGen)
            append(" answerSdpBytes=").append(state.answerSdpBytes)
            append(" answerSdpLines=").append(state.answerSdpLines)
        }
    }

    fun correlationFieldsFromTag(tag: String): String {
        val edgeKey = edgeKeyFromDiagnosticTag(tag) ?: return "tag=$tag"
        return correlationFields(edgeKey)
    }

    fun formatWatchdogGap(edgeKey: String, pcHash: Int? = null): String {
        val state = attempts[edgeKey]
        val gap = state?.let { classifyWatchdogGap(it) }
        val missing = gap?.missing ?: "UNKNOWN"
        val gapState = gap?.state ?: "UNKNOWN"
        val lastEvent = state?.lastEvent ?: "NONE"
        val pcField = pcHash?.let { " pcHash=$it" } ?: ""
        val elapsedField = state?.nativeCallElapsedMs?.let { " nativeCallElapsedMs=$it" } ?: ""
        val domainElapsedField = state?.domainExecutionElapsedMs?.let { " domainExecutionElapsedMs=$it" } ?: ""
        val holderField = gap?.holderEdge?.let { " holderEdge=$it" } ?: ""
        return buildString {
            append("SRD_WATCHDOG_GAP ")
            append(correlationFields(edgeKey))
            append(" lastEvent=").append(lastEvent)
            append(" missing=").append(missing)
            append(" state=").append(gapState)
            append(holderField)
            append(pcField)
            append(elapsedField)
            append(domainElapsedField)
        }
    }

    internal data class WatchdogGapClassification(
        val missing: String,
        val state: String,
        val holderEdge: String? = null,
    )

    internal fun classifyWatchdogGap(state: AttemptState): WatchdogGapClassification {
        val holderEdge = state.ctx.edgeKey
        if (state.sawDomainLeaseRequest &&
            !state.sawDomainLeaseGranted &&
            !state.sawDomainLeaseBusy &&
            !state.sawDomainLeaseQuarantined
        ) {
            return WatchdogGapClassification(
                missing = "DOMAIN_LEASE",
                state = "WAITING_FOR_DOMAIN",
            )
        }
        if (state.sawDomainExecutionEnter && state.sawNativeCallEnter && !state.sawNativeCallExit) {
            return WatchdogGapClassification(
                missing = "NATIVE_CALL_EXIT",
                state = "NATIVE_DOMAIN_EXECUTION_ACTIVE",
                holderEdge = holderEdge,
            )
        }
        if (state.sawNativeCallExit && !state.sawJniReturn) {
            return WatchdogGapClassification(
                missing = "JNI_RETURN",
                state = "POST_NATIVE_CALL_WAIT",
                holderEdge = holderEdge,
            )
        }
        if (!state.sawNativeCallEnter &&
            !state.sawJniReturn &&
            state.lastEvent in NATIVE_CALL_PENDING_EVENTS
        ) {
            return WatchdogGapClassification(
                missing = "NATIVE_CALL_ENTER",
                state = if (state.sawDomainExecutionEnter) {
                    "NATIVE_DOMAIN_EXECUTION_ACTIVE"
                } else {
                    "PRE_NATIVE_CALL"
                },
                holderEdge = holderEdge,
            )
        }
        if (!state.sawJniReturn && state.lastEvent in JNI_RETURN_PENDING_EVENTS) {
            return WatchdogGapClassification(
                missing = "JNI_RETURN",
                state = if (state.sawDomainExecutionEnter) {
                    "NATIVE_DOMAIN_EXECUTION_ACTIVE"
                } else {
                    "PRE_NATIVE_CALL"
                },
                holderEdge = holderEdge,
            )
        }
        if (state.sawJniReturn && !state.sawCallback) {
            return WatchdogGapClassification(
                missing = "CALLBACK",
                state = "POST_NATIVE_CALLBACK_WAIT",
                holderEdge = holderEdge,
            )
        }
        if (!state.sawMutexReleased &&
            state.lastEvent in MUTEX_HELD_EVENTS &&
            state.lastEvent != "SRD_MUTEX_RELEASED"
        ) {
            return WatchdogGapClassification(
                missing = "MUTEX_RELEASED",
                state = "POST_NATIVE_CALLBACK_WAIT",
                holderEdge = holderEdge,
            )
        }
        if (!state.sawExit) {
            return WatchdogGapClassification(
                missing = "SRD_EXIT",
                state = "POST_NATIVE_CALLBACK_WAIT",
                holderEdge = holderEdge,
            )
        }
        return WatchdogGapClassification(
            missing = "SRD_EXIT",
            state = "POST_NATIVE_CALLBACK_WAIT",
            holderEdge = holderEdge,
        )
    }

    internal fun primaryMissing(state: AttemptState): String = classifyWatchdogGap(state).missing

    private val NATIVE_CALL_PENDING_EVENTS = setOf(
        "SRD_MUTEX_ACQUIRED"
    )

    private val JNI_RETURN_PENDING_EVENTS = setOf(
        "SRD_ENTER",
        "SRD_MUTEX_WAIT_BEGIN",
        "SRD_MUTEX_ACQUIRED"
    )

    private val MUTEX_HELD_EVENTS = setOf(
        "SRD_MUTEX_ACQUIRED",
        "SRD_NATIVE_CALL_ENTER",
        "SRD_NATIVE_CALL_EXIT",
        "SRD_JNI_RETURN",
        "SRD_CALLBACK_ENTER",
        "SRD_CALLBACK_FAILURE"
    )

    /** Test-only visibility. */
    internal fun peekAttempt(edgeKey: String): AttemptState? = attempts[edgeKey]
}
