package com.talkback.core.conference.session.integration

import android.util.Log
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.session.JitterSlotDomainSnapshot
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.PlayoutFunnelSnapshot
import com.talkback.core.conference.aqc.PcmLevelMetrics
import com.talkback.core.conference.session.SessionMediaRuntimeSnapshot
import com.talkback.core.conference.transport.Rfc6464TxVoiceLevel

/**
 * Behavior-neutral Phase A shadow runtime OBS for field adjudication (PA-SR4 / PA-SR6).
 * Tag: MEETING_MEDIA_SHADOW · prefix: PROFILE01_SHADOW_RUNTIME
 */
object Profile01ShadowRuntimeObservability {
    /** PA-SR5 field adjudication: wiring incarnationId == wire sourceGeneration. */
    fun memberBindingFields(binding: MemberBindingFact): Map<String, String> =
        memberIdentityFields(
            moduleId = binding.moduleId,
            incarnationId = binding.incarnationId,
            ssrc = binding.ssrc,
        )

    fun memberIdentityFields(
        moduleId: String,
        incarnationId: Long,
        ssrc: Int? = null,
    ): Map<String, String> {
        val fields =
            mutableMapOf(
                "moduleId" to moduleId,
                "sourceGeneration" to incarnationId.toString(),
                "incarnationId" to incarnationId.toString(),
            )
        ssrc?.let { fields["ssrc"] = it.toString() }
        return fields
    }

    @Volatile
    var activeSessionId: String? = null

    @Volatile
    var activeConferenceId: String? = null

    @Volatile
    var activeMediaKeyEpoch: Long? = null

    private val ingressLoggedSessions = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val playoutLoggedSessions = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val txLoggedSessions = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val rxActivityLoggedSessions = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val playoutCycleLoggedSessions = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val playoutStarvationCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val ingressFunnelCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val playoutFunnelCycleCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val lastEmittedResolvedMixSlots =
        java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastSuccessfulFunnelSnapshots =
        java.util.concurrent.ConcurrentHashMap<String, PlayoutFunnelSnapshot>()
    private val starvationOnsetLoggedSessions = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val rxAdmissionWindows =
        java.util.concurrent.ConcurrentHashMap<String, RxAdmissionWindowAccumulator>()
    private val rxAdmissionFirstAdmittedLogged =
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val rxAdmissionFirstRejectReasonLogged =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Boolean>>()

    private class RxAdmissionWindowAccumulator {
        var windowStartMs: Long = System.currentTimeMillis()
        var admitted: Int = 0
        var rejected: Int = 0
        var keyMismatch: Int = 0
        var aeadFail: Int = 0
        var otherReject: Int = 0
    }

    private class IngressAnomalyAccumulator {
        var lateForPlayout: Int = 0
        var reorderDisplacementExceeded: Int = 0
    }

    internal enum class ResolvedMixSlotDeltaClass {
        UNKNOWN,
        NORMAL,
        FORWARD_SMALL,
        FORWARD_LARGE,
        BACKWARD,
    }

    internal fun classifyResolvedMixSlotDelta(delta: Long): ResolvedMixSlotDeltaClass =
        when {
            delta < 0L -> ResolvedMixSlotDeltaClass.BACKWARD
            delta <= 1L -> ResolvedMixSlotDeltaClass.NORMAL
            delta <= 100L -> ResolvedMixSlotDeltaClass.FORWARD_SMALL
            else -> ResolvedMixSlotDeltaClass.FORWARD_LARGE
        }

    private val ingressAnomalyAccumulators =
        java.util.concurrent.ConcurrentHashMap<String, IngressAnomalyAccumulator>()

    fun recordIngressFrameAdmit(
        sessionId: String,
        frameAdmit: FrameAdmitDisposition?,
    ) {
        when (frameAdmit) {
            FrameAdmitDisposition.LATE_FOR_PLAYOUT ->
                ingressAnomalyAccumulators
                    .computeIfAbsent(sessionId) { IngressAnomalyAccumulator() }
                    .lateForPlayout++
            FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED ->
                ingressAnomalyAccumulators
                    .computeIfAbsent(sessionId) { IngressAnomalyAccumulator() }
                    .reorderDisplacementExceeded++
            else -> Unit
        }
    }

    fun snapshotIngressAnomalyCounts(sessionId: String): Pair<Int, Int> {
        val acc = ingressAnomalyAccumulators[sessionId] ?: return 0 to 0
        return acc.lateForPlayout to acc.reorderDisplacementExceeded
    }

    fun resetIngressAnomalyCounts(sessionId: String) {
        val acc = ingressAnomalyAccumulators[sessionId] ?: return
        acc.lateForPlayout = 0
        acc.reorderDisplacementExceeded = 0
    }

    private val ingressVoiceCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun maybeLogIngressVoiceLevelSample(
        sessionId: String,
        sourceIdentity: String,
        voiceActive: Boolean,
        audioLevel: Int,
        wireOctet: Int,
    ) {
        val key = "$sessionId|$sourceIdentity"
        val ingressOrdinal =
            ingressVoiceCounters
                .computeIfAbsent(key) { java.util.concurrent.atomic.AtomicLong(0L) }
                .incrementAndGet()
        if (ingressOrdinal == 1L || ingressOrdinal % 25L == 0L) {
            logPhase(
                phase = "SHADOW_TX_VOICE_LEVEL",
                sessionId = sessionId,
                moduleId = sourceIdentity,
                fields =
                    mapOf(
                        "ingressOrdinal" to ingressOrdinal.toString(),
                        "voiceActive" to voiceActive.toString(),
                        "audioLevel" to audioLevel.toString(),
                        "wireOctet" to "0x%02X".format(wireOctet),
                    ),
            )
        }
    }

    fun bindActiveSession(
        sessionId: String,
        conferenceId: String?,
        mediaKeyEpoch: Long,
    ) {
        activeSessionId = sessionId
        activeConferenceId = conferenceId
        activeMediaKeyEpoch = mediaKeyEpoch
    }

    fun clearActiveSession(sessionId: String) {
        if (activeSessionId == sessionId) {
            activeSessionId = null
            activeConferenceId = null
            activeMediaKeyEpoch = null
        }
        ingressLoggedSessions.remove(sessionId)
        playoutLoggedSessions.remove(sessionId)
        txLoggedSessions.remove(sessionId)
        rxActivityLoggedSessions.remove(sessionId)
        playoutCycleLoggedSessions.remove(sessionId)
        playoutStarvationCounters.remove(sessionId)
        ingressFunnelCounters.remove(sessionId)
        playoutFunnelCycleCounters.remove(sessionId)
        lastSuccessfulFunnelSnapshots.remove(sessionId)
        starvationOnsetLoggedSessions.remove(sessionId)
        flushRxAdmissionSummary(sessionId, force = true)
        rxAdmissionWindows.remove(sessionId)
        rxAdmissionFirstAdmittedLogged.remove(sessionId)
        rxAdmissionFirstRejectReasonLogged.remove(sessionId)
        aqcTxMetricCounters.remove(sessionId)
        aqcFallbackCounters.remove(sessionId)
        shadowTxEncodePcmLevelCounters.remove(sessionId)
        shadowTxCapturePcmCounters.remove(sessionId)
        shadowTxCaptureLastWallMs.remove(sessionId)
        shadowTxCaptureLastLogWallMs.remove(sessionId)
        shadowTxCaptureCostWindows.remove(sessionId)
        shadowTxCaptureEnterNs.remove(sessionId)
        shadowTxEncodeQueueCounters.remove(sessionId)
        shadowTxEncodeDequeueCounters.remove(sessionId)
        m01GatedTxEncodeCounters.remove(sessionId)
        M01GatedTxEncodeLevelProcessor.clearSession(sessionId)
    }

    fun logShadowPlayoutArmed(
        sessionId: String,
        outcome: String,
        anchorMs: Long?,
    ) {
        val fields =
            mutableMapOf("outcome" to outcome)
        anchorMs?.let { fields["anchorMs"] = it.toString() }
        logPhase(
            phase = "SHADOW_PLAYOUT_ARMED",
            sessionId = sessionId,
            fields = fields,
        )
    }

    fun logShadowPlayoutDisarmed(
        sessionId: String,
        mixCyclesExecuted: Long,
        tickFailures: Long,
    ) {
        logPhase(
            phase = "SHADOW_PLAYOUT_DISARMED",
            sessionId = sessionId,
            fields =
                mapOf(
                    "mixCyclesExecuted" to mixCyclesExecuted.toString(),
                    "tickFailures" to tickFailures.toString(),
                ),
        )
    }

    fun maybeLogShadowPlayoutCycle(
        sessionId: String,
        tickMediaTimeMs: Long,
        slot: Long,
        liveDecoders: Int,
        successfulPlayoutWrites: Long,
        audioTrackOwner: String = "SHADOW_METRICS_ONLY",
    ) {
        val first = playoutCycleLoggedSessions.putIfAbsent(sessionId, true) == null
        if (!first && successfulPlayoutWrites % 25L != 0L) return
        logPhase(
            phase = "SHADOW_PLAYOUT_CYCLE",
            sessionId = sessionId,
            conferenceId = activeConferenceId,
            mediaKeyEpoch = activeMediaKeyEpoch,
            fields =
                mapOf(
                    "tickMediaTimeMs" to tickMediaTimeMs.toString(),
                    "slot" to slot.toString(),
                    "liveDecoders" to liveDecoders.toString(),
                    "successfulPlayoutWrites" to successfulPlayoutWrites.toString(),
                    "audioTrackOwner" to audioTrackOwner,
                ),
        )
    }

    fun logShadowPlayoutTickFailed(
        sessionId: String,
        reason: String,
        detail: String?,
        stackTrace: String? = null,
    ) {
        val fields =
            mutableMapOf(
                "reason" to reason,
                "detail" to (detail ?: "null"),
            )
        stackTrace?.let { fields["stackTrace"] = it }
        logPhase(
            phase = "SHADOW_PLAYOUT_TICK_FAILED",
            sessionId = sessionId,
            fields = fields,
        )
    }

    fun maybeLogPlayoutBufferStarvation(
        sessionId: String,
        funnel: PlayoutFunnelSnapshot,
        liveDecoders: Int,
        resolveSkipReason: String? = null,
    ) {
        val count =
            playoutStarvationCounters
                .computeIfAbsent(sessionId) { java.util.concurrent.atomic.AtomicLong(0L) }
                .incrementAndGet()
        val first = count == 1L
        if (first) {
            maybeLogPlayoutFunnelStarvationOnset(sessionId, funnel, liveDecoders)
        }
        if (!first && count % 50L != 0L) return
        logPlayoutFunnelPhase(
            phase = "SHADOW_PLAYOUT_BUFFER_STARVATION",
            sessionId = sessionId,
            funnel = funnel,
            extra =
                buildMap {
                    put("starvationTicks", count.toString())
                    put("liveDecoders", liveDecoders.toString())
                    if (resolveSkipReason != null) {
                        put("funnelResolveSkipReason", resolveSkipReason)
                    }
                },
        )
    }

    fun maybeLogIngressFunnel(
        sessionId: String,
        sourceIdentity: String,
        mediaSlot: Long,
        frameAdmit: FrameAdmitDisposition?,
        jitter: JitterSlotDomainSnapshot?,
    ) {
        val count =
            ingressFunnelCounters
                .computeIfAbsent(sessionId) { java.util.concurrent.atomic.AtomicLong(0L) }
                .incrementAndGet()
        val first = count == 1L
        if (!first && count % 25L != 0L) return
        val fields =
            mutableMapOf(
                "ingressOrdinal" to count.toString(),
                "mediaSlot" to mediaSlot.toString(),
                "jitterOutcome" to (frameAdmit?.name ?: "null"),
            )
        jitter?.let { fields.putAll(jitterFields("jitter", it)) }
        logPhase(
            phase = "INGRESS_FUNNEL",
            sessionId = sessionId,
            conferenceId = activeConferenceId,
            mediaKeyEpoch = activeMediaKeyEpoch,
            moduleId = sourceIdentity,
            fields = fields,
        )
    }

    fun maybeLogPlayoutFunnelCycle(
        sessionId: String,
        funnel: PlayoutFunnelSnapshot,
        liveDecoders: Int,
        successfulPlayoutWrites: Long,
        audioTrackOwner: String,
    ) {
        lastSuccessfulFunnelSnapshots[sessionId] = funnel
        starvationOnsetLoggedSessions.remove(sessionId)
        val count =
            playoutFunnelCycleCounters
                .computeIfAbsent(sessionId) { java.util.concurrent.atomic.AtomicLong(0L) }
                .incrementAndGet()
        val first = count == 1L
        if (!first && count % 25L != 0L) return
        logPlayoutFunnelPhase(
            phase = "PLAYOUT_FUNNEL_CYCLE",
            sessionId = sessionId,
            funnel = funnel,
            extra =
                mapOf(
                    "cycleOrdinal" to count.toString(),
                    "liveDecoders" to liveDecoders.toString(),
                    "successfulPlayoutWrites" to successfulPlayoutWrites.toString(),
                    "audioTrackOwner" to audioTrackOwner,
                ),
        )
    }

    private fun maybeLogPlayoutFunnelStarvationOnset(
        sessionId: String,
        funnel: PlayoutFunnelSnapshot,
        liveDecoders: Int,
    ) {
        if (starvationOnsetLoggedSessions.putIfAbsent(sessionId, true) != null) return
        val prior = lastSuccessfulFunnelSnapshots[sessionId]
        val fields =
            mutableMapOf(
                "liveDecoders" to liveDecoders.toString(),
            )
        fields.putAll(funnelSummaryFields("starvation", funnel))
        prior?.let { fields.putAll(funnelSummaryFields("lastCycle", it)) }
        logPhase(
            phase = "PLAYOUT_FUNNEL_STARVATION_ONSET",
            sessionId = sessionId,
            conferenceId = activeConferenceId,
            mediaKeyEpoch = activeMediaKeyEpoch,
            fields = fields,
        )
    }

    private fun logPlayoutFunnelPhase(
        phase: String,
        sessionId: String,
        funnel: PlayoutFunnelSnapshot,
        extra: Map<String, String> = emptyMap(),
    ) {
        val fields = mutableMapOf<String, String>()
        fields.putAll(funnelSummaryFields("funnel", funnel))
        fields.putAll(extra)
        logPhase(
            phase = phase,
            sessionId = sessionId,
            conferenceId = activeConferenceId,
            mediaKeyEpoch = activeMediaKeyEpoch,
            fields = fields,
        )
    }

    private fun funnelSummaryFields(
        prefix: String,
        funnel: PlayoutFunnelSnapshot,
    ): Map<String, String> {
        val fields =
            mutableMapOf(
                "${prefix}TickMediaTimeMs" to funnel.tickMediaTimeMs.toString(),
                "${prefix}PlayoutTargetSlot" to (funnel.playoutTargetSlot?.toString() ?: "null"),
                "${prefix}ResolvedMixSlot" to (funnel.resolvedMixSlot?.toString() ?: "null"),
                "${prefix}SelectedTopK" to funnel.selectedTopK.joinToString(",").ifEmpty { "none" },
                "${prefix}ActiveJitterSources" to funnel.activeJitterSources.toString(),
                "${prefix}AdmittedCount" to funnel.admittedCount.toString(),
            )
        funnel.perSource.forEachIndexed { index, source ->
            val sourcePrefix = "${prefix}Source$index"
            fields["${sourcePrefix}Id"] = source.sourceIdentity
            fields.putAll(jitterFields(sourcePrefix, source))
        }
        return fields
    }

    private fun jitterFields(
        prefix: String,
        jitter: JitterSlotDomainSnapshot,
    ): Map<String, String> =
        mapOf(
            "${prefix}NextExpectedSlot" to (jitter.nextExpectedSlot?.toString() ?: "null"),
            "${prefix}BySlotSize" to jitter.bySlotSize.toString(),
            "${prefix}EarliestBufferedSlot" to (jitter.earliestBufferedSlot?.toString() ?: "null"),
            "${prefix}LatestBufferedSlot" to (jitter.latestBufferedSlot?.toString() ?: "null"),
            "${prefix}Executable" to jitter.executable.toString(),
        )

    fun logShadowPlayoutFailed(
        sessionId: String,
        reason: String,
        detail: String?,
    ) {
        logPhase(
            phase = "SHADOW_PLAYOUT_FAILED",
            sessionId = sessionId,
            fields =
                mapOf(
                    "reason" to reason,
                    "detail" to (detail ?: "null"),
                ),
        )
    }

    fun logShadowRxArmed(
        sessionId: String,
        outcome: String,
    ) {
        logPhase(
            phase = "SHADOW_RX_ARMED",
            sessionId = sessionId,
            fields = mapOf("outcome" to outcome),
        )
    }

    fun maybeLogShadowRxActivity(
        sessionId: String,
        receivedDatagrams: Long,
    ) {
        if (receivedDatagrams <= 0L) return
        val first = rxActivityLoggedSessions.putIfAbsent(sessionId, true) == null
        if (!first && receivedDatagrams % 25L != 0L) return
        logPhase(
            phase = "SHADOW_RX_ACTIVITY",
            sessionId = sessionId,
            fields = mapOf("receivedDatagrams" to receivedDatagrams.toString()),
        )
    }

    fun logShadowRxAdmission(
        sessionId: String,
        outcome: String,
        reason: String? = null,
    ) {
        when (outcome) {
            "ADMITTED" ->
                if (rxAdmissionFirstAdmittedLogged.putIfAbsent(sessionId, true) == null) {
                    logPhase(
                        phase = "SHADOW_RX_ADMISSION",
                        sessionId = sessionId,
                        fields = mapOf("outcome" to outcome),
                    )
                }
            "REJECTED" -> {
                val reasonKey = reason ?: "UNKNOWN"
                val perSession =
                    rxAdmissionFirstRejectReasonLogged.computeIfAbsent(sessionId) {
                        java.util.concurrent.ConcurrentHashMap()
                    }
                if (perSession.putIfAbsent(reasonKey, true) == null) {
                    logPhase(
                        phase = "SHADOW_RX_ADMISSION",
                        sessionId = sessionId,
                        fields = mapOf("outcome" to outcome, "reason" to reasonKey),
                    )
                }
            }
            else ->
                logPhase(
                    phase = "SHADOW_RX_ADMISSION",
                    sessionId = sessionId,
                    fields = mapOf("outcome" to outcome),
                )
        }

        val nowMs = System.currentTimeMillis()
        val window =
            rxAdmissionWindows.computeIfAbsent(sessionId) {
                RxAdmissionWindowAccumulator().apply { windowStartMs = nowMs }
            }
        synchronized(window) {
            when (outcome) {
                "ADMITTED" -> window.admitted++
                "REJECTED" -> {
                    window.rejected++
                    when (reason) {
                        "sourceAdmissionKey48 mismatch" -> window.keyMismatch++
                        "AEAD authentication or decryption failed" -> window.aeadFail++
                        else -> window.otherReject++
                    }
                }
            }
            if (nowMs - window.windowStartMs >= RX_ADMISSION_SUMMARY_WINDOW_MS) {
                emitRxAdmissionSummary(sessionId, window)
                resetRxAdmissionWindow(window, nowMs)
            }
        }
    }

    private fun flushRxAdmissionSummary(
        sessionId: String,
        force: Boolean,
    ) {
        val window = rxAdmissionWindows[sessionId] ?: return
        synchronized(window) {
            if (!force && window.admitted == 0 && window.rejected == 0) return
            emitRxAdmissionSummary(sessionId, window)
            resetRxAdmissionWindow(window, System.currentTimeMillis())
        }
    }

    private fun emitRxAdmissionSummary(
        sessionId: String,
        window: RxAdmissionWindowAccumulator,
    ) {
        if (window.admitted == 0 && window.rejected == 0) return
        logPhase(
            phase = "SHADOW_RX_ADMISSION_SUMMARY",
            sessionId = sessionId,
            fields =
                mapOf(
                    "windowMs" to RX_ADMISSION_SUMMARY_WINDOW_MS.toString(),
                    "admitted" to window.admitted.toString(),
                    "rejected" to window.rejected.toString(),
                    "keyMismatch" to window.keyMismatch.toString(),
                    "aeadFail" to window.aeadFail.toString(),
                    "other" to window.otherReject.toString(),
                ),
        )
    }

    private fun resetRxAdmissionWindow(
        window: RxAdmissionWindowAccumulator,
        nowMs: Long,
    ) {
        window.windowStartMs = nowMs
        window.admitted = 0
        window.rejected = 0
        window.keyMismatch = 0
        window.aeadFail = 0
        window.otherReject = 0
    }

    fun logShadowRxFailed(
        sessionId: String,
        reason: String,
        detail: String?,
    ) {
        logPhase(
            phase = "SHADOW_RX_FAILED",
            sessionId = sessionId,
            fields =
                mapOf(
                    "reason" to reason,
                    "detail" to (detail ?: "null"),
                ),
        )
    }

    fun logHostSessionProjected(
        sessionId: String,
        outcome: String,
        conferenceId: String? = null,
        mediaKeyEpoch: Long? = null,
    ) {
        logPhase(
            phase = "HOST_SESSION_PROJECTED",
            sessionId = sessionId,
            conferenceId = conferenceId,
            mediaKeyEpoch = mediaKeyEpoch,
            fields = mapOf("outcome" to outcome),
        )
    }

    fun maybeLogShadowTxActivity(
        sessionId: String,
        moduleId: String,
        packetsSent: Long,
        protectedSent: Long,
        sendErrors: Long,
    ) {
        if (packetsSent <= 0L && protectedSent <= 0L) return
        val first = txLoggedSessions.putIfAbsent(sessionId, true) == null
        if (!first && packetsSent % 25L != 0L) return
        logPhase(
            phase = "SHADOW_TX_ACTIVITY",
            sessionId = sessionId,
            moduleId = moduleId,
            fields =
                mapOf(
                    "packetsSent" to packetsSent.toString(),
                    "protectedSent" to protectedSent.toString(),
                    "sendErrors" to sendErrors.toString(),
                    "audioTrackOwner" to "SHADOW_METRICS_ONLY",
                ),
        )
    }

    private val aqcTxMetricCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val aqcFallbackCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val shadowTxEncodePcmLevelCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val shadowTxCapturePcmCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val shadowTxCaptureLastWallMs =
        java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val shadowTxCaptureLastLogWallMs =
        java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val shadowTxCaptureEnterNs =
        java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val shadowTxCaptureCostWindows =
        java.util.concurrent.ConcurrentHashMap<String, CaptureCallbackCostWindow>()
    private val shadowTxEncodeQueueCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val shadowTxEncodeDequeueCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val shadowTxEncodeJobCostCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val shadowTxPcmS1LevelCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val d2SendChainTickCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val m01GatedTxEncodeCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun maybeLogM01GatedTxEncodeLevel(
        sessionId: String,
        moduleId: String,
        result: M01GatedTxEncodeLevelFrameResult,
    ) {
        if (!M01GatedTxEncodeLevelRuntime.enabled) return
        val n =
            m01GatedTxEncodeCounters
                .getOrPut(sessionId) { java.util.concurrent.atomic.AtomicLong(0) }
                .incrementAndGet()
        if (n != 1L && n % 25L != 0L) return
        logPhase(
            phase = "SHADOW_TX_ENCODE_PCM_LEVEL_POST_GAIN",
            sessionId = sessionId,
            moduleId = moduleId,
            fields =
                mapOf(
                    "packetOrdinal" to n.toString(),
                    "gateOpen" to result.gateOpen.toString(),
                    "gainApplied" to result.gainApplied.toString(),
                    "limiterApplied" to result.limiterApplied.toString(),
                    "limiterScale" to "%.3f".format(result.limiterScale),
                    "inRmsDbfs" to if (result.inputRmsDbfs.isFinite()) "%.1f".format(result.inputRmsDbfs) else "-inf",
                    "outRmsDbfs" to if (result.outputRmsDbfs.isFinite()) "%.1f".format(result.outputRmsDbfs) else "-inf",
                    "wallTimeMs" to System.currentTimeMillis().toString(),
                ),
        )
    }

    fun logD2TxPathSecond(
        sessionId: String,
        epochSecond: Long,
        postForward: Int,
        preForward: Int,
        postFormatReject: Int,
        preFormatReject: Int,
        suppressedPre: Int,
        muxEnabled: Boolean,
    ) {
        if (!D2SendChainRuntime.captureChainObserveEnabled) return
        logPhase(
            phase = "D2_TX_PATH_SECOND",
            sessionId = sessionId,
            moduleId = null,
            fields =
                mapOf(
                    "epochSecond" to epochSecond.toString(),
                    "muxEnabled" to muxEnabled.toString(),
                    "postForward" to postForward.toString(),
                    "preForward" to preForward.toString(),
                    "postFormatReject" to postFormatReject.toString(),
                    "preFormatReject" to preFormatReject.toString(),
                    "suppressedPre" to suppressedPre.toString(),
                    "lastPath" to D2TxPathTelemetry.lastForwardedPath,
                    "wallTimeMs" to System.currentTimeMillis().toString(),
                ),
        )
    }

    fun logD2S2EncodeSecond(
        sessionId: String,
        moduleId: String,
        epochSecond: Long,
        frameCount: Int,
        speechFrameCount: Int,
        maxRmsDbfs: Double,
        p90SpeechRmsDbfs: Double,
        meanRmsDbfs: Double,
        txPath: String,
    ) {
        if (!D2SendChainRuntime.captureChainObserveEnabled) return
        logPhase(
            phase = "D2_S2_ENCODE_SECOND",
            sessionId = sessionId,
            moduleId = moduleId,
            fields =
                mapOf(
                    "epochSecond" to epochSecond.toString(),
                    "frameCount" to frameCount.toString(),
                    "speechFrameCount" to speechFrameCount.toString(),
                    "maxRmsDbfs" to if (maxRmsDbfs.isFinite()) "%.1f".format(maxRmsDbfs) else "-inf",
                    "p90SpeechRmsDbfs" to if (p90SpeechRmsDbfs.isFinite()) "%.1f".format(p90SpeechRmsDbfs) else "na",
                    "meanRmsDbfs" to if (meanRmsDbfs.isFinite()) "%.1f".format(meanRmsDbfs) else "-inf",
                    "txPath" to txPath,
                    "wallTimeMs" to System.currentTimeMillis().toString(),
                ),
        )
    }

    fun maybeLogD2SendChainTick(
        sessionId: String,
        moduleId: String,
        result: D2SendChainFrameResult,
    ) {
        if (!D2SendChainRuntime.sendHeadroomEnabled) return
        val n =
            d2SendChainTickCounters
                .getOrPut(sessionId) { java.util.concurrent.atomic.AtomicLong(0) }
                .incrementAndGet()
        if (n != 1L && n % 25L != 0L) return
        val deltaDb =
            if (result.inputRmsDbfs.isFinite() && result.outputRmsDbfs.isFinite()) {
                result.outputRmsDbfs - result.inputRmsDbfs
            } else {
                Double.NaN
            }
        logPhase(
            phase = "D2_SEND_CHAIN_S1_TO_S2",
            sessionId = sessionId,
            moduleId = moduleId,
            fields =
                mapOf(
                    "packetOrdinal" to n.toString(),
                    "headroomApplied" to result.headroomApplied.toString(),
                    "limiterScale" to "%.3f".format(result.limiterScale),
                    "inRmsDbfs" to if (result.inputRmsDbfs.isFinite()) "%.1f".format(result.inputRmsDbfs) else "-inf",
                    "outRmsDbfs" to if (result.outputRmsDbfs.isFinite()) "%.1f".format(result.outputRmsDbfs) else "-inf",
                    "deltaRmsDb" to if (deltaDb.isFinite()) "%.2f".format(deltaDb) else "nan",
                    "wallTimeMs" to System.currentTimeMillis().toString(),
                ),
        )
    }

    /**
     * Capture callback cadence (pre-assembler). Frame 1 and every 25th callback per session.
     * Does not infer capture clock from send counts.
     */
    fun maybeLogShadowTxCapturePcm(
        sessionId: String,
        moduleId: String,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
    ) {
        val n =
            shadowTxCapturePcmCounters
                .getOrPut(sessionId) { java.util.concurrent.atomic.AtomicLong(0) }
                .incrementAndGet()
        val nowMs = System.currentTimeMillis()
        val lastCallbackMs = shadowTxCaptureLastWallMs.put(sessionId, nowMs)
        if (n != 1L && n % 25L != 0L) return
        val lastLogMs = shadowTxCaptureLastLogWallMs.put(sessionId, nowMs)
        val formatAccepted =
            bitsPerSample == 16 && sampleRate == 48_000 && numberOfChannels == 1
        logPhase(
            phase = "SHADOW_TX_CAPTURE_PCM",
            sessionId = sessionId,
            moduleId = moduleId,
            fields =
                mapOf(
                    "callbackOrdinal" to n.toString(),
                    "sampleRate" to sampleRate.toString(),
                    "channels" to numberOfChannels.toString(),
                    "bits" to bitsPerSample.toString(),
                    "numberOfFrames" to numberOfFrames.toString(),
                    "intervalMs" to (if (lastCallbackMs == null) -1L else nowMs - lastCallbackMs).toString(),
                    "spanMs" to (if (lastLogMs == null) -1L else nowMs - lastLogMs).toString(),
                    "formatAccepted" to formatAccepted.toString(),
                    "wallTimeMs" to nowMs.toString(),
                ),
        )
    }

    /**
     * Per-callback costs aggregated in-memory; emit 1-in-25.
     * Does not change capture/encode/send behavior.
     */
    fun recordShadowTxCaptureCallbackCost(
        sessionId: String,
        moduleId: String,
        intervalNs: Long,
        preUs: Long,
        assembleUs: Long,
        encodeUs: Long,
        sendUs: Long,
        totalUs: Long,
        encoded: Boolean,
    ) {
        val window =
            shadowTxCaptureCostWindows.getOrPut(sessionId) { CaptureCallbackCostWindow() }
        val n = window.add(intervalNs, preUs, assembleUs, encodeUs, sendUs, totalUs, encoded)
        if (n != 1L && n % 25L != 0L) return
        val snap = window.snapshot()
        logPhase(
            phase = "SHADOW_TX_CAPTURE_CALLBACK_COST",
            sessionId = sessionId,
            moduleId = moduleId,
            fields =
                mapOf(
                    "callbackOrdinal" to n.toString(),
                    "winN" to snap.winN.toString(),
                    "encodedN" to snap.encodedN.toString(),
                    "intervalP50Us" to snap.intervalP50Us.toString(),
                    "intervalP95Us" to snap.intervalP95Us.toString(),
                    "preP50Us" to snap.preP50Us.toString(),
                    "assembleP50Us" to snap.assembleP50Us.toString(),
                    "assembleP95Us" to snap.assembleP95Us.toString(),
                    "encodeP50Us" to snap.encodeP50Us.toString(),
                    "encodeP95Us" to snap.encodeP95Us.toString(),
                    "encodeMaxUs" to snap.encodeMaxUs.toString(),
                    "sendP50Us" to snap.sendP50Us.toString(),
                    "sendP95Us" to snap.sendP95Us.toString(),
                    "sendMaxUs" to snap.sendMaxUs.toString(),
                    "totalP50Us" to snap.totalP50Us.toString(),
                    "totalP95Us" to snap.totalP95Us.toString(),
                    "totalMaxUs" to snap.totalMaxUs.toString(),
                    "over10ms" to snap.over10ms.toString(),
                    "longThenGap" to snap.longThenGap.toString(),
                    "thisTotalUs" to totalUs.toString(),
                    "thisEncodeUs" to encodeUs.toString(),
                    "thisSendUs" to sendUs.toString(),
                    "thisIntervalUs" to (intervalNs / 1000L).toString(),
                    "wallTimeMs" to System.currentTimeMillis().toString(),
                ),
        )
        window.resetWindowCounters()
    }

    fun maybeLogShadowTxEncodeQueue(
        sessionId: String,
        moduleId: String,
        depth: Int,
        maxDepth: Int,
        overflow: Long,
        overflowDropOldest: Long,
        droppedOnStop: Long,
        queuedUs: Long,
        accepted: Boolean,
        droppedOldest: Boolean,
    ) {
        val n =
            shadowTxEncodeQueueCounters
                .getOrPut(sessionId) { java.util.concurrent.atomic.AtomicLong(0) }
                .incrementAndGet()
        if (accepted && !droppedOldest && n != 1L && n % 25L != 0L) return
        logPhase(
            phase = "SHADOW_TX_ENCODE_QUEUE",
            sessionId = sessionId,
            moduleId = moduleId,
            fields =
                mapOf(
                    "offerOrdinal" to n.toString(),
                    "depth" to depth.toString(),
                    "maxDepth" to maxDepth.toString(),
                    "overflow" to overflow.toString(),
                    "overflowDropOldest" to overflowDropOldest.toString(),
                    "droppedOnStop" to droppedOnStop.toString(),
                    "queuedUs" to queuedUs.toString(),
                    "accepted" to accepted.toString(),
                    "droppedOldest" to droppedOldest.toString(),
                    "capacity" to "4",
                    "wallTimeMs" to System.currentTimeMillis().toString(),
                ),
        )
    }

    fun maybeLogShadowTxEncodeJobCost(
        sessionId: String,
        moduleId: String,
        outcome: String,
        preEncodeUs: Long,
        opusUs: Long,
        protectSendUs: Long,
        jobUs: Long,
        sent: Boolean,
    ) {
        val n =
            shadowTxEncodeJobCostCounters
                .getOrPut(sessionId) { java.util.concurrent.atomic.AtomicLong(0) }
                .incrementAndGet()
        if (n != 1L && n % 25L != 0L) return
        val accounted =
            (if (preEncodeUs >= 0) preEncodeUs else 0L) +
                (if (opusUs >= 0) opusUs else 0L) +
                (if (protectSendUs >= 0) protectSendUs else 0L)
        logPhase(
            phase = "SHADOW_TX_ENCODE_JOB_COST",
            sessionId = sessionId,
            moduleId = moduleId,
            fields =
                mapOf(
                    "jobOrdinal" to n.toString(),
                    "outcome" to outcome,
                    "preEncodeUs" to preEncodeUs.toString(),
                    "opusUs" to opusUs.toString(),
                    "protectSendUs" to protectSendUs.toString(),
                    "jobUs" to jobUs.toString(),
                    "accountedUs" to accounted.toString(),
                    "unaccountedUs" to (jobUs - accounted).toString(),
                    "sent" to sent.toString(),
                    "wallTimeMs" to System.currentTimeMillis().toString(),
                ),
        )
    }

    fun maybeLogShadowTxEncodeDequeued(
        sessionId: String,
        moduleId: String,
        queuedUs: Long,
        depth: Int,
        overflow: Long,
    ) {
        val n =
            shadowTxEncodeDequeueCounters
                .getOrPut(sessionId) { java.util.concurrent.atomic.AtomicLong(0) }
                .incrementAndGet()
        if (n != 1L && n % 25L != 0L) return
        logPhase(
            phase = "SHADOW_TX_ENCODE_QUEUE",
            sessionId = sessionId,
            moduleId = moduleId,
            fields =
                mapOf(
                    "dequeueOrdinal" to n.toString(),
                    "queuedUs" to queuedUs.toString(),
                    "depth" to depth.toString(),
                    "overflow" to overflow.toString(),
                    "wallTimeMs" to System.currentTimeMillis().toString(),
                ),
        )
    }

    fun markShadowTxCaptureEnterNs(sessionId: String): Long {
        val nowNs = System.nanoTime()
        val prev = shadowTxCaptureEnterNs.put(sessionId, nowNs)
        return if (prev == null) -1L else nowNs - prev
    }

    internal fun percentileUs(sortedAsc: LongArray, p: Int): Long {
        if (sortedAsc.isEmpty()) return -1L
        val rank = ((p / 100.0) * (sortedAsc.size - 1)).toInt().coerceIn(0, sortedAsc.lastIndex)
        return sortedAsc[rank]
    }

    /**
     * B1-OBSERVE / D2 S2: actual [pcmForEncode] level before Opus.
     * Logs on frame 1 and every 25th encode frame per session.
     */
    fun maybeLogShadowTxEncodePcmLevel(
        sessionId: String,
        moduleId: String,
        pcmForEncode: ShortArray,
    ) {
        val packetOrdinal =
            shadowTxEncodePcmLevelCounters
                .getOrPut(sessionId) { java.util.concurrent.atomic.AtomicLong(0) }
                .incrementAndGet()
        if (packetOrdinal != 1L && packetOrdinal % 25L != 0L) return
        val levelDbov = Rfc6464TxVoiceLevel.frameLevelDbov(pcmForEncode)
        val rmsDbfs = PcmLevelMetrics.rmsDbfs(pcmForEncode)
        logPhase(
            phase = "SHADOW_TX_ENCODE_PCM_LEVEL",
            sessionId = sessionId,
            moduleId = moduleId,
            fields =
                mapOf(
                    "packetOrdinal" to packetOrdinal.toString(),
                    "levelDbov" to levelDbov.toString(),
                    "rmsDbfs" to if (rmsDbfs.isFinite()) "%.1f".format(rmsDbfs) else "-inf",
                    "wallTimeMs" to System.currentTimeMillis().toString(),
                    "frameSamples" to pcmForEncode.size.toString(),
                ),
        )
    }

    /** AQC Phase 2: encode-authority PCM metrics (rate-limited). */
    fun maybeLogAqcTxMetrics(
        sessionId: String,
        moduleId: String,
        metrics: com.talkback.core.conference.aqc.TxProcessingChain.TxMetrics,
    ) {
        val n =
            aqcTxMetricCounters
                .getOrPut(sessionId) { java.util.concurrent.atomic.AtomicLong(0) }
                .incrementAndGet()
        if (n != 1L && n % 25L != 0L) return
        logPhase(
            phase = "AQC_TX_METRICS",
            sessionId = sessionId,
            moduleId = moduleId,
            fields =
                mapOf(
                    "tapPoint" to metrics.tapPoint.name,
                    "inPeak" to metrics.inputPeakAbs.toString(),
                    "outPeak" to metrics.outputPeakAbs.toString(),
                    "inClip" to metrics.inputClipSamples.toString(),
                    "outClip" to metrics.outputClipSamples.toString(),
                    "inRmsDb" to "%.1f".format(metrics.inputRmsDbfs),
                    "outRmsDb" to "%.1f".format(metrics.outputRmsDbfs),
                    "limiterScale" to "%.3f".format(metrics.limiterScale),
                    "limiterApplied" to metrics.limiterApplied.toString(),
                    "frameOrdinal" to n.toString(),
                ),
        )
    }

    /** AQC Phase 2: resolve-null continuous CN write (rate-limited). */
    fun maybeLogAqcPlayoutFallback(
        sessionId: String,
        wrote: Boolean,
        samples: Int,
        kind: String,
    ) {
        val n =
            aqcFallbackCounters
                .getOrPut(sessionId) { java.util.concurrent.atomic.AtomicLong(0) }
                .incrementAndGet()
        if (n != 1L && n % 50L != 0L) return
        logPhase(
            phase = "AQC_PLAYOUT_FALLBACK",
            sessionId = sessionId,
            fields =
                mapOf(
                    "wrote" to wrote.toString(),
                    "samples" to samples.toString(),
                    "kind" to kind,
                    "ordinal" to n.toString(),
                ),
        )
    }

    fun logShadowTxFenced(
        sessionId: String,
        reason: String,
    ) {
        logPhase(
            phase = "SHADOW_TX_FENCED",
            sessionId = sessionId,
            fields = mapOf("reason" to reason),
        )
    }

    fun logShadowTxBindingInstalled(
        sessionId: String,
        moduleId: String,
    ) {
        logPhase(
            phase = "SHADOW_TX_BINDING_INSTALLED",
            sessionId = sessionId,
            fields = mapOf("moduleId" to moduleId),
        )
    }

    fun logShadowTxFailed(
        sessionId: String,
        reason: String,
        detail: String?,
    ) {
        logPhase(
            phase = "SHADOW_TX_FAILED",
            sessionId = sessionId,
            fields =
                mapOf(
                    "reason" to reason,
                    "detail" to (detail ?: "null"),
                ),
        )
    }

    fun maybeLogIngressActivity(
        sourceIdentity: String,
        packetsReceived: Long,
        ingressAccepted: Long,
        activeJitterSources: Int,
        liveDecoders: Int,
        successfulPlayoutWrites: Long,
    ) {
        val sessionId = activeSessionId ?: return
        if (ingressAccepted <= 0L && packetsReceived <= 0L) return
        val first = ingressLoggedSessions.putIfAbsent(sessionId, true) == null
        if (!first && ingressAccepted % 25L != 0L) return
        logIngressActivity(
            sessionId = sessionId,
            sourceIdentity = sourceIdentity,
            packetsReceived = packetsReceived,
            ingressAccepted = ingressAccepted,
            activeJitterSources = activeJitterSources,
            liveDecoders = liveDecoders,
            successfulPlayoutWrites = successfulPlayoutWrites,
        )
    }

    fun maybeLogPlayoutActivity(successfulPlayoutWrites: Long) {
        val sessionId = activeSessionId ?: return
        if (successfulPlayoutWrites <= 0L) return
        val first = playoutLoggedSessions.putIfAbsent(sessionId, true) == null
        if (!first && successfulPlayoutWrites % 25L != 0L) return
        logPhase(
            phase = "PLAYOUT_ACTIVITY",
            sessionId = sessionId,
            conferenceId = activeConferenceId,
            mediaKeyEpoch = activeMediaKeyEpoch,
            fields =
                mapOf(
                    "successfulPlayoutWrites" to successfulPlayoutWrites.toString(),
                    "audioTrackOwner" to "SHADOW_METRICS_ONLY",
                ),
        )
    }

    fun logPhase(
        phase: String,
        sessionId: String,
        conferenceId: String? = null,
        mediaKeyEpoch: Long? = null,
        moduleId: String? = null,
        fields: Map<String, String> = emptyMap(),
    ) {
        val parts = mutableListOf<String>()
        parts += "PROFILE01_SHADOW_RUNTIME"
        parts += "phase=$phase"
        parts += "sessionId=$sessionId"
        conferenceId?.let { parts += "conferenceId=$it" }
        mediaKeyEpoch?.let { parts += "mediaKeyEpoch=$it" }
        moduleId?.let { parts += "moduleId=$it" }
        fields.forEach { (k, v) -> parts += "$k=$v" }
        emit(parts.joinToString(" "))
    }

    fun logRuntimeSnapshot(
        phase: String,
        sessionId: String,
        conferenceId: String?,
        mediaKeyEpoch: Long?,
        snapshot: SessionMediaRuntimeSnapshot,
        extra: Map<String, String> = emptyMap(),
    ) {
        val fields =
            mutableMapOf(
                "ingressBlocked" to snapshot.ingressBlocked.toString(),
                "transportScopeActive" to snapshot.transportScopeActive.toString(),
                "catalogEntries" to snapshot.catalogEntries.toString(),
                "expectedMembers" to snapshot.expectedMembers.toString(),
                "pendingSourceMembers" to snapshot.pendingSourceMembers.toString(),
                "admittedCount" to snapshot.admittedCount.toString(),
                "activeJitterSources" to snapshot.activeJitterSources.toString(),
                "liveDecoders" to snapshot.liveDecoders.toString(),
                "jitterBufferCount" to snapshot.jitterBufferCount.toString(),
            )
        fields.putAll(extra)
        logPhase(
            phase = phase,
            sessionId = sessionId,
            conferenceId = conferenceId,
            mediaKeyEpoch = mediaKeyEpoch,
            fields = fields,
        )
    }

    fun logRxDatagramSourceBucket(
        windowStartMs: Long,
        windowEndMs: Long,
        total: Long,
        localEcho: Long,
        remote: Long,
        topRemote: String,
    ) {
        val sessionId = activeSessionId ?: return
        logPhase(
            phase = "RX_DATAGRAM_SOURCE_BUCKET",
            sessionId = sessionId,
            conferenceId = activeConferenceId,
            mediaKeyEpoch = activeMediaKeyEpoch,
            fields =
                mapOf(
                    "windowStartMs" to windowStartMs.toString(),
                    "windowEndMs" to windowEndMs.toString(),
                    "datagramTotal" to total.toString(),
                    "localEcho" to localEcho.toString(),
                    "remote" to remote.toString(),
                    "topRemote" to topRemote,
                    "observeOnly" to "P1_OPT3",
                ),
        )
    }

    fun logIngressActivity(
        sessionId: String,
        sourceIdentity: String,
        packetsReceived: Long,
        ingressAccepted: Long,
        activeJitterSources: Int,
        liveDecoders: Int,
        successfulPlayoutWrites: Long,
    ) {
        logPhase(
            phase = "INGRESS_ACTIVITY",
            sessionId = sessionId,
            moduleId = sourceIdentity,
            fields =
                mapOf(
                    "packetsReceived" to packetsReceived.toString(),
                    "ingressAccepted" to ingressAccepted.toString(),
                    "activeJitterSources" to activeJitterSources.toString(),
                    "liveDecoders" to liveDecoders.toString(),
                    "successfulPlayoutWrites" to successfulPlayoutWrites.toString(),
                    "audioTrackOwner" to "SHADOW_METRICS_ONLY",
                ),
        )
    }

    fun logTeardown(
        sessionId: String,
        conferenceId: String?,
        mediaKeyEpoch: Long?,
        snapshot: SessionMediaRuntimeSnapshot?,
    ) {
        if (snapshot == null) {
            logPhase(
                phase = "TEARDOWN_EMPTY",
                sessionId = sessionId,
                conferenceId = conferenceId,
                mediaKeyEpoch = mediaKeyEpoch,
                fields = mapOf("runtimeSnapshot" to "ABSENT"),
            )
            return
        }
        logRuntimeSnapshot(
            phase = "TEARDOWN_EMPTY",
            sessionId = sessionId,
            conferenceId = conferenceId,
            mediaKeyEpoch = mediaKeyEpoch,
            snapshot = snapshot,
            extra = mapOf("audioTrackOwner" to "SHADOW_METRICS_ONLY"),
        )
    }

    fun logProductionAudioTrackFence(
        owner: String,
        sessionHint: String? = null,
        audioSessionId: Int? = null,
        bufferFrames: Int? = null,
    ) {
        val parts = mutableListOf<String>()
        parts += "PROFILE01_SHADOW_RUNTIME"
        parts += "phase=AUDIO_TRACK_OWNERSHIP"
        parts += "owner=$owner"
        parts += "playoutOwner=PRODUCTION"
        sessionHint?.let { parts += "sessionHint=$it" }
        audioSessionId?.let { parts += "audioSessionId=$it" }
        bufferFrames?.let { parts += "bufferFrames=$it" }
        parts += "trackId=na"
        emit(parts.joinToString(" "))
    }

    private fun emit(line: String) {
        try {
            Log.i(MeetingProductMediaShadow.LOG_TAG, line)
        } catch (_: Throwable) {
        }
    }

    private data class CaptureCallbackCostSnapshot(
        val winN: Int,
        val encodedN: Int,
        val intervalP50Us: Long,
        val intervalP95Us: Long,
        val preP50Us: Long,
        val assembleP50Us: Long,
        val assembleP95Us: Long,
        val encodeP50Us: Long,
        val encodeP95Us: Long,
        val encodeMaxUs: Long,
        val sendP50Us: Long,
        val sendP95Us: Long,
        val sendMaxUs: Long,
        val totalP50Us: Long,
        val totalP95Us: Long,
        val totalMaxUs: Long,
        val over10ms: Int,
        val longThenGap: Int,
    )

    private class CaptureCallbackCostWindow {
        private val totals = LongArray(RING)
        private val intervals = LongArray(RING)
        private val pres = LongArray(RING)
        private val assembles = LongArray(RING)
        private val encodes = LongArray(RING)
        private var encodeFill = 0
        private val sends = LongArray(RING)
        private var sendFill = 0
        private var write = 0
        private var filled = 0
        private var ordinal = 0L
        private var prevTotalUs = -1L
        private var over10ms = 0
        private var longThenGap = 0
        private var encodedN = 0

        fun add(
            intervalNs: Long,
            preUs: Long,
            assembleUs: Long,
            encodeUs: Long,
            sendUs: Long,
            totalUs: Long,
            encoded: Boolean,
        ): Long {
            val idx = write
            totals[idx] = totalUs
            intervals[idx] = if (intervalNs < 0L) -1L else intervalNs / 1000L
            pres[idx] = preUs
            assembles[idx] = assembleUs
            write = (write + 1) % RING
            if (filled < RING) filled++
            if (encoded) {
                encodes[encodeFill % RING] = encodeUs
                encodeFill++
                sends[sendFill % RING] = sendUs
                sendFill++
                encodedN++
            }
            if (totalUs >= 10_000L) over10ms++
            if (prevTotalUs >= 8_000L && intervalNs >= 15_000_000L) longThenGap++
            prevTotalUs = totalUs
            ordinal++
            return ordinal
        }

        fun snapshot(): CaptureCallbackCostSnapshot {
            val totalSorted = copyFilled(totals, filled)
            val intervalSorted = copyFilled(intervals, filled).filter { it >= 0L }.toLongArray()
            val preSorted = copyFilled(pres, filled)
            val assembleSorted = copyFilled(assembles, filled)
            val encodeCount = encodeFill.coerceAtMost(RING)
            val sendCount = sendFill.coerceAtMost(RING)
            val encodeSorted = copyFilled(encodes, encodeCount)
            val sendSorted = copyFilled(sends, sendCount)
            totalSorted.sort()
            intervalSorted.sort()
            preSorted.sort()
            assembleSorted.sort()
            encodeSorted.sort()
            sendSorted.sort()
            return CaptureCallbackCostSnapshot(
                winN = filled,
                encodedN = encodedN,
                intervalP50Us = percentile(intervalSorted, 50),
                intervalP95Us = percentile(intervalSorted, 95),
                preP50Us = percentile(preSorted, 50),
                assembleP50Us = percentile(assembleSorted, 50),
                assembleP95Us = percentile(assembleSorted, 95),
                encodeP50Us = percentile(encodeSorted, 50),
                encodeP95Us = percentile(encodeSorted, 95),
                encodeMaxUs = encodeSorted.lastOrNull() ?: -1L,
                sendP50Us = percentile(sendSorted, 50),
                sendP95Us = percentile(sendSorted, 95),
                sendMaxUs = sendSorted.lastOrNull() ?: -1L,
                totalP50Us = percentile(totalSorted, 50),
                totalP95Us = percentile(totalSorted, 95),
                totalMaxUs = totalSorted.lastOrNull() ?: -1L,
                over10ms = over10ms,
                longThenGap = longThenGap,
            )
        }

        fun resetWindowCounters() {
            over10ms = 0
            longThenGap = 0
            encodedN = 0
        }

        private fun copyFilled(src: LongArray, count: Int): LongArray {
            if (count <= 0) return LongArray(0)
            return src.copyOf(count)
        }

        private fun percentile(sortedAsc: LongArray, p: Int): Long {
            if (sortedAsc.isEmpty()) return -1L
            val rank = ((p / 100.0) * (sortedAsc.lastIndex)).toInt().coerceIn(0, sortedAsc.lastIndex)
            return sortedAsc[rank]
        }
    }

    private const val RING = 128
    private const val RX_ADMISSION_SUMMARY_WINDOW_MS = 1_000L
}
