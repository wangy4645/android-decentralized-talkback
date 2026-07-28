package com.talkback.core.util

import com.talkback.core.webrtc.MediaBearerScope

/**
 * MEDIA RECOVERY CAUSAL TRACE — read-only lineage from recovery attempt through
 * media dispatch, signaling, and ICE transport (ADR-0022 prep).
 *
 * Does not gate recovery, membership, floor, or UI.
 *
 * Grep: `MEDIA_SIGNAL_`, `MEDIA_ICE_CANDIDATE_`, `RECOVERY_ICE_RESTART_DISPATCHED`,
 * `RECOVERY_OFFER_SENT`, `RECOVERY_OFFER_RECEIVED`, `WEBRTC_NEGOTIATION`
 */
object MediaRecoveryCausalTrace {

    /** Receiver-side ingress decision for recovery / ICE-restart GROUP_JOIN offers (4.3-D-1). */
    enum class OfferIngressDecision {
        ACCEPT_ICE_RESTART,
        ACCEPT_FIRST_MESH,
        QUEUED_NO_SESSION,
        DROP_DUPLICATE_ICE_CONNECTED,
        DROP_ICE_RESTART_THROTTLED,
        DROP_DECODE_FAILED,
        DROP_NO_CALLEE,
        DROP_GROUP_BUSY
    }

    data class Context(
        val sessionId: String,
        val sessionTraceId: String,
        val scope: MediaBearerScope,
        val remoteModuleId: String,
        val remoteEndpointId: String? = null,
        val recoveryAttemptId: Long? = null,
        val obligationGeneration: Long? = null,
        val conferenceGeneration: Long? = null,
        val pcGeneration: Long? = null,
        val transportGeneration: Long? = null,
        val iceRestart: Boolean = false
    )

    private var logSink: ((String) -> Unit)? = null

    internal fun resetForTest(sink: ((String) -> Unit)? = null) {
        logSink = sink
    }

    private fun log(message: String) {
        val sink = logSink
        if (sink != null) {
            sink(message)
        } else {
            TalkbackLog.i(message)
        }
    }

    private fun formatContext(prefix: String, ctx: Context): String {
        val sb = StringBuilder(prefix)
        sb.append(" session=").append(ctx.sessionId)
        sb.append(" sessionTraceId=").append(ctx.sessionTraceId)
        sb.append(" scope=").append(ctx.scope.name)
        sb.append(" remote=").append(ctx.remoteModuleId)
        ctx.remoteEndpointId?.let { sb.append(" remoteEndpoint=").append(it) }
        ctx.recoveryAttemptId?.let { sb.append(" attempt=").append(it) }
        ctx.obligationGeneration?.let { sb.append(" obligationGen=").append(it) }
        ctx.conferenceGeneration?.let { sb.append(" conferenceGeneration=").append(it) }
        ctx.pcGeneration?.let { sb.append(" pcGeneration=").append(it) }
        ctx.transportGeneration?.let { sb.append(" transportGeneration=").append(it) }
        if (ctx.iceRestart) {
            sb.append(" iceRestart=true")
        }
        return sb.toString()
    }

    fun recoveryIceRestartDispatched(ctx: Context) {
        log(formatContext("RECOVERY_ICE_RESTART_DISPATCHED", ctx))
    }

    fun mediaSignalOfferSent(ctx: Context) {
        log(formatContext("MEDIA_SIGNAL_OFFER_SENT", ctx))
    }

    /**
     * 4.3-D-1 observation: host/peer recovery offer emission with lineage binding.
     * Does not change send behavior.
     */
    fun recoveryOfferSent(
        ctx: Context,
        joinIntent: String,
        transportOutcome: String,
        signalingEpoch: Long? = null
    ) {
        val sb = StringBuilder(formatContext("RECOVERY_OFFER_SENT", ctx))
        sb.append(" joinIntent=").append(joinIntent)
        sb.append(" transportOutcome=").append(transportOutcome)
        signalingEpoch?.let { sb.append(" signalingEpoch=").append(it) }
        log(sb.toString())
    }

    fun mediaSignalOfferReceived(ctx: Context, joinIntent: String? = null) {
        val suffix = joinIntent?.let { " joinIntent=$it" }.orEmpty()
        log(formatContext("MEDIA_SIGNAL_OFFER_RECEIVED", ctx) + suffix)
    }

    /**
     * 4.3-D-1 observation: every GROUP_JOIN offer ingress path must emit a decision
     * (accept or drop reason). Does not change accept/drop behavior.
     */
    fun recoveryOfferReceived(
        ctx: Context,
        decision: OfferIngressDecision,
        joinIntent: String? = null,
        localIceState: String? = null,
        localAttemptId: Long? = null,
        localObligationGen: Long? = null,
        detail: String? = null
    ) {
        val sb = StringBuilder(formatContext("RECOVERY_OFFER_RECEIVED", ctx))
        sb.append(" decision=").append(decision.name)
        joinIntent?.let { sb.append(" joinIntent=").append(it) }
        localIceState?.let { sb.append(" localIce=").append(it) }
        localAttemptId?.let { sb.append(" localAttempt=").append(it) }
        localObligationGen?.let { sb.append(" localObligationGen=").append(it) }
        detail?.let { sb.append(" detail=").append(it) }
        log(sb.toString())
    }

    fun mediaIceCandidateGenerated(ctx: Context, candidateIndex: Int? = null) {
        val suffix = candidateIndex?.let { " candidateIndex=$it" }.orEmpty()
        log(formatContext("MEDIA_ICE_CANDIDATE_GENERATED", ctx) + suffix)
    }

    fun mediaSignalCandidateSent(ctx: Context) {
        log(formatContext("MEDIA_SIGNAL_CANDIDATE_SENT", ctx))
    }

    fun mediaSignalCandidateReceived(ctx: Context, queued: Boolean = false) {
        val suffix = if (queued) " queued=true" else ""
        log(formatContext("MEDIA_SIGNAL_CANDIDATE_RECEIVED", ctx) + suffix)
    }

    fun mediaIceCandidateApplied(ctx: Context, queued: Boolean = false) {
        val suffix = if (queued) " queued=false applied=true" else " applied=true"
        log(formatContext("MEDIA_ICE_CANDIDATE_APPLIED", ctx) + suffix)
    }

    /**
     * 4.3-E observation: PeerConnection negotiation snapshot at a named seam
     * (e.g. ICE_RESTART_DISPATCHED_BEFORE_OFFER). Does not change behavior.
     */
    fun webrtcNegotiationSnapshot(
        ctx: Context,
        reason: String,
        signalingState: String,
        iceConnectionState: String,
        connectionState: String,
        localDescriptionType: String?,
        remoteDescriptionType: String?,
        negotiationRole: String? = null
    ) {
        val sb = StringBuilder(formatContext("WEBRTC_NEGOTIATION", ctx))
        sb.append(" op=SNAPSHOT reason=").append(reason)
        negotiationRole?.let { sb.append(" role=").append(it) }
        sb.append(" signalingState=").append(signalingState)
        sb.append(" iceConnectionState=").append(iceConnectionState)
        sb.append(" connectionState=").append(connectionState)
        sb.append(" localDesc=").append(localDescriptionType ?: "NONE")
        sb.append(" remoteDesc=").append(remoteDescriptionType ?: "NONE")
        log(sb.toString())
    }
}
