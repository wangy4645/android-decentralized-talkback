package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.SourcePullEmptyDiagnostics
import com.talkback.core.conference.session.SourcePullEmptyReason

/**
 * Per-source pull failure RCA (observability only).
 */
object Profile01SourcePullEmptyTelemetry {
    const val PHASE = "SOURCE_PULL_EMPTY"

    @Volatile
    var testLogSink: ((String) -> Unit)? = null

    fun resetForTest() {
        testLogSink = null
    }

    internal fun maybeLogPullObservation(
        sessionId: String,
        mixSessionKey: String,
        sessionPlayoutSlot: Long,
        nowMs: Long,
        observation: SourcePullEmptyDiagnostics.PullObservation,
    ) {
        if (observation.emptyReason == SourcePullEmptyReason.REAL) return
        val owner = Profile01A3MixOutputTelemetry.audioTrackOwnerLabel()
        if (owner != Profile01A3MixOutputTelemetry.OWNER_MULTICAST_PRODUCTION &&
            testLogSink == null
        ) {
            return
        }
        val fields =
            linkedMapOf(
                "mixSessionKey" to mixSessionKey,
                "sessionPlayoutSlot" to sessionPlayoutSlot.toString(),
                "nowMs" to nowMs.toString(),
                "sourceKey" to observation.sourceIdentity,
                "incarnationId" to observation.incarnationId.toString(),
                "pullSlot" to (observation.pullSlot?.toString() ?: "na"),
                "expectedMediaTimeMs" to observation.slotMediaTimeMs.toString(),
                "pullResult" to (observation.pullResult?.name ?: "SKIPPED"),
                "emptyReason" to observation.emptyReason.name,
                "queueDepth" to observation.queueDepth.toString(),
                "nextExpectedSlot" to (observation.nextExpectedSlot?.toString() ?: "na"),
                "earliestBufferedSlot" to (observation.earliestBufferedSlot?.toString() ?: "na"),
                "latestBufferedSlot" to (observation.latestBufferedSlot?.toString() ?: "na"),
                "deferred" to observation.deferred.toString(),
                "decodeEligible" to observation.decodeEligible.toString(),
            )
        Profile01ShadowRuntimeObservability.logPhase(
            phase = PHASE,
            sessionId = sessionId,
            fields = fields,
        )
        testLogSink?.invoke(
            buildString {
                append(PHASE)
                fields.forEach { (k, v) -> append(' ').append(k).append('=').append(v) }
            },
        )
    }
}
