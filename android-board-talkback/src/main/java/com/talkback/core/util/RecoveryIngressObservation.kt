package com.talkback.core.util

import com.talkback.core.model.ConferenceJoinIntent
import com.talkback.core.model.GroupSessionPayload
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * PR5-2c-D Slice-1: lineage-scoped ingress observation windows (fact producer only).
 *
 * Produces [RecoveryDeliveryFact] REMOTE_INGRESS_OBSERVED / REMOTE_INGRESS_ABSENT.
 * No retry policy, no admission — observation boundary only.
 */
object RecoveryIngressObservation {

    internal var windowDeadlineMs: Long = 3_000L

    private data class WindowKey(
        val offerLineageId: String,
        val deliveryAttemptId: Long,
        val sessionId: String,
        val from: String,
        val to: String
    )

    private enum class OutboundState {
        OPEN,
        CLOSED_OBSERVED,
        CLOSED_ABSENT,
        CLOSED_CONFIRMED,
        CLOSED_EXHAUSTED,
        CLOSED_SUPERSEDED
    }

    private data class OutboundWindow(
        var state: OutboundState = OutboundState.OPEN,
        var timerFuture: ScheduledFuture<*>? = null
    )

    private val outboundWindows = ConcurrentHashMap<WindowKey, OutboundWindow>()
    private val exhaustedLineages = ConcurrentHashMap.newKeySet<String>()
    private val supersededLineages = ConcurrentHashMap.newKeySet<String>()
    private val inboundObservedEmitted = ConcurrentHashMap.newKeySet<WindowKey>()

    private var scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var observationLogSink: ((String) -> Unit)? = null

    internal fun resetForTest(
        deadlineMs: Long = 200L,
        observationLog: ((String) -> Unit)? = null
    ) {
        scheduler.shutdownNow()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        outboundWindows.clear()
        exhaustedLineages.clear()
        supersededLineages.clear()
        inboundObservedEmitted.clear()
        windowDeadlineMs = deadlineMs
        observationLogSink = observationLog
    }

    internal fun shutdownForTest() {
        scheduler.shutdownNow()
        observationLogSink = null
    }

    internal fun fireWindowDeadlineForTest(identity: RecoveryDeliveryFact.Identity, sessionId: String?) {
        onWindowDeadline(windowKey(identity, sessionId.orEmpty()))
    }

    fun onLocalAccepted(identity: RecoveryDeliveryFact.Identity, sessionId: String?) {
        val sid = sessionId.orEmpty()
        if (supersededLineages.contains(identity.offerLineageId)) return
        if (exhaustedLineages.contains(identity.offerLineageId)) return
        val key = windowKey(identity, sid)
        val existing = outboundWindows[key]
        if (existing != null && existing.state != OutboundState.OPEN) return
        val window = existing ?: OutboundWindow()
        window.state = OutboundState.OPEN
        window.timerFuture?.cancel(false)
        window.timerFuture = scheduler.schedule(
            { onWindowDeadline(key) },
            windowDeadlineMs,
            TimeUnit.MILLISECONDS
        )
        outboundWindows[key] = window
    }

    fun onRemoteIngressReceive(
        envelope: SignalEnvelope,
        localModuleId: String,
        sessionId: String? = envelope.sessionId
    ) {
        if (envelope.type != SignalType.GROUP_JOIN) return
        val payload = GroupSessionPayload.decode(envelope.payload) ?: return
        if (payload.joinIntent != ConferenceJoinIntent.RECOVERY_REATTACH) return
        val lineageId = payload.offerLineageId?.takeIf { it.isNotBlank() } ?: return
        val deliveryAttemptId = payload.deliveryAttemptId.coerceAtLeast(1L)
        val from = envelope.from.moduleId.value
        val to = localModuleId
        val sid = sessionId.orEmpty()

        val identity = RecoveryDeliveryFact.Identity(
            offerLineageId = lineageId,
            recoveryAttemptId = payload.restartAttemptId ?: 0L,
            obligationGeneration = payload.obligationGeneration ?: 0L,
            deliveryAttemptId = deliveryAttemptId,
            from = from,
            to = to
        )
        handleIngressEvidence(identity, sid)
    }

    /** Test hook: ingress evidence without JSON decode (JVM unit tests). */
    internal fun onIngressEvidenceForTest(identity: RecoveryDeliveryFact.Identity, sessionId: String?) {
        handleIngressEvidence(identity, sessionId.orEmpty())
    }

    private fun handleIngressEvidence(identity: RecoveryDeliveryFact.Identity, sid: String) {
        val lineageId = identity.offerLineageId
        val deliveryAttemptId = identity.deliveryAttemptId
        val from = identity.from
        val to = identity.to

        if (exhaustedLineages.contains(lineageId)) {
            return
        }
        if (supersededLineages.contains(lineageId)) {
            logObservationOnly(
                "RECOVERY_REMOTE_INGRESS_LATE_OBSERVATION_ONLY offerLineageId=$lineageId " +
                    "deliveryAttemptId=$deliveryAttemptId from=$from to=$to reason=LINEAGE_SUPERSEDED"
            )
            return
        }

        val key = WindowKey(lineageId, deliveryAttemptId, sid, from, to)
        val outbound = outboundWindows[key]

        when (outbound?.state) {
            OutboundState.CLOSED_ABSENT,
            OutboundState.CLOSED_CONFIRMED,
            OutboundState.CLOSED_EXHAUSTED,
            OutboundState.CLOSED_SUPERSEDED -> {
                logObservationOnly(
                    "RECOVERY_REMOTE_INGRESS_LATE_OBSERVATION_ONLY offerLineageId=$lineageId " +
                        "deliveryAttemptId=$deliveryAttemptId from=$from to=$to " +
                        "reason=${outbound.state.name}"
                )
                return
            }
            OutboundState.CLOSED_OBSERVED -> {
                logObservationOnly(
                    "RECOVERY_REMOTE_INGRESS_LATE_OBSERVATION_ONLY offerLineageId=$lineageId " +
                        "deliveryAttemptId=$deliveryAttemptId from=$from to=$to reason=ALREADY_OBSERVED"
                )
                return
            }
            OutboundState.OPEN -> {
                closeOutboundWindow(key, OutboundState.CLOSED_OBSERVED)
            }
            null -> {
                // Receiver-only ingress (no outbound window on this device).
            }
        }

        if (inboundObservedEmitted.contains(key)) return
        inboundObservedEmitted.add(key)
        RecoveryDeliveryFact.emitRemoteIngressObserved(identity, sid.takeIf { it.isNotBlank() })
    }

    fun onDeliveryConfirmed(identity: RecoveryDeliveryFact.Identity, sessionId: String?) {
        closeOutboundIfOpen(identity, sessionId, OutboundState.CLOSED_CONFIRMED)
    }

    fun onDeliveryExhausted(identity: RecoveryDeliveryFact.Identity, sessionId: String?) {
        exhaustedLineages.add(identity.offerLineageId)
        closeOutboundIfOpen(identity, sessionId, OutboundState.CLOSED_EXHAUSTED)
    }

    fun onLineageSuperseded(offerLineageId: String) {
        supersededLineages.add(offerLineageId)
        outboundWindows.keys.filter { it.offerLineageId == offerLineageId }.forEach { key ->
            val window = outboundWindows[key]
            if (window != null && window.state == OutboundState.OPEN) {
                logObservationOnly(
                    "RECOVERY_INGRESS_WINDOW_CLOSED offerLineageId=${key.offerLineageId} " +
                        "deliveryAttemptId=${key.deliveryAttemptId} state=CLOSED_SUPERSEDED " +
                        "from=${key.from} to=${key.to}"
                )
                closeOutboundWindow(key, OutboundState.CLOSED_SUPERSEDED)
            }
        }
    }

    fun onReachabilityHint() {
        // PR5-2c-D e3: reachability may re-evaluate OPEN window in policy slice — not producer.
    }

    private fun onWindowDeadline(key: WindowKey) {
        val window = outboundWindows[key] ?: return
        if (window.state != OutboundState.OPEN) return
        window.timerFuture?.cancel(false)
        window.timerFuture = null
        window.state = OutboundState.CLOSED_ABSENT
        val identity = RecoveryDeliveryFact.Identity(
            offerLineageId = key.offerLineageId,
            recoveryAttemptId = 0L,
            obligationGeneration = 0L,
            deliveryAttemptId = key.deliveryAttemptId,
            from = key.from,
            to = key.to
        )
        RecoveryDeliveryFact.emitRemoteIngressAbsent(
            identity,
            key.sessionId.takeIf { it.isNotBlank() },
            reason = "WINDOW_DEADLINE"
        )
    }

    private fun closeOutboundIfOpen(
        identity: RecoveryDeliveryFact.Identity,
        sessionId: String?,
        closeState: OutboundState
    ) {
        val key = windowKey(identity, sessionId.orEmpty())
        val window = outboundWindows[key]
        if (window == null || window.state != OutboundState.OPEN) return
        closeOutboundWindow(key, closeState)
    }

    private fun closeOutboundWindow(key: WindowKey, closeState: OutboundState) {
        val window = outboundWindows[key] ?: return
        window.timerFuture?.cancel(false)
        window.timerFuture = null
        window.state = closeState
    }

    private fun windowKey(identity: RecoveryDeliveryFact.Identity, sessionId: String): WindowKey =
        WindowKey(
            offerLineageId = identity.offerLineageId,
            deliveryAttemptId = identity.deliveryAttemptId,
            sessionId = sessionId,
            from = identity.from,
            to = identity.to
        )

    private fun logObservationOnly(message: String) {
        val sink = observationLogSink
        if (sink != null) {
            sink(message)
        } else {
            TalkbackLog.i(message)
        }
    }
}
