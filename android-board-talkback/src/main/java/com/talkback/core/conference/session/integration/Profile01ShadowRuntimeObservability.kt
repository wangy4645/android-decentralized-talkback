package com.talkback.core.conference.session.integration

import android.util.Log
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.session.JitterSlotDomainSnapshot
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.PlayoutFunnelSnapshot
import com.talkback.core.conference.session.SessionMediaRuntimeSnapshot

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
                mapOf(
                    "starvationTicks" to count.toString(),
                    "liveDecoders" to liveDecoders.toString(),
                ),
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
    ) {
        val parts = mutableListOf<String>()
        parts += "PROFILE01_SHADOW_RUNTIME"
        parts += "phase=AUDIO_TRACK_OWNERSHIP"
        parts += "owner=$owner"
        sessionHint?.let { parts += "sessionHint=$it" }
        emit(parts.joinToString(" "))
    }

    private fun emit(line: String) {
        try {
            Log.i(MeetingProductMediaShadow.LOG_TAG, line)
        } catch (_: Throwable) {
        }
    }

    private const val RX_ADMISSION_SUMMARY_WINDOW_MS = 1_000L
}
