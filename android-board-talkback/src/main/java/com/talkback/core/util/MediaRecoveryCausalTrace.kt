package com.talkback.core.util

import com.talkback.core.webrtc.MediaBearerScope

/**
 * MEDIA RECOVERY CAUSAL TRACE — read-only lineage from recovery attempt through
 * media dispatch, signaling, and ICE transport (ADR-0022 prep).
 *
 * Does not gate recovery, membership, floor, or UI.
 *
 * Grep: `MEDIA_SIGNAL_`, `MEDIA_ICE_CANDIDATE_`, `RECOVERY_ICE_RESTART_DISPATCHED`
 */
object MediaRecoveryCausalTrace {

    data class Context(
        val sessionId: String,
        val sessionTraceId: String,
        val scope: MediaBearerScope,
        val remoteModuleId: String,
        val remoteEndpointId: String? = null,
        val recoveryAttemptId: Long? = null,
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

    fun mediaSignalOfferReceived(ctx: Context, joinIntent: String? = null) {
        val suffix = joinIntent?.let { " joinIntent=$it" }.orEmpty()
        log(formatContext("MEDIA_SIGNAL_OFFER_RECEIVED", ctx) + suffix)
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
}
