package com.talkback.core.session

import com.talkback.core.qos.IceConnectivity
import com.talkback.core.model.RecoveryHandlerOutcome
import com.talkback.core.util.RecoveryDeliveryFact
import com.talkback.core.util.RecoveryControlReconciliationFact
import com.talkback.core.util.RecoveryControlReconciliationMembershipObservation
import com.talkback.core.util.RecoveryNegotiationAuthority
import com.talkback.core.util.RecoveryNegotiationObservation
import com.talkback.core.util.SuppressSuccessorAttemptDebugInjection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-edge conference recovery policy and state (ADR-0021 R4鈥揜18).
 * Control-plane reattach precedes bounded media ICE restart; termination cancels all edges.
 */
class ConferenceEdgeRecoveryController internal constructor(
  private val localModuleId: String = "LOCAL",
    private val debounceMs: Long = 3_000L,
    private val iceRestartTimeoutMs: Long = 10_000L,
    private val attemptBudgetMs: Long = 15_000L,
    /**
     * Observation window after failed-media residency (ADR-0022 R28-H).
     * `obligationDeadlineAt = attemptTerminalAt + observationWindow`. Must be meaningfully
     * longer than the soak's ~4s premature prune; tests may inject a short window.
     */
    private val observationWindowMs: Long = 30_000L,
    private val tombstoneTtlMs: Long = 120_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scheduler: ScheduledExecutorService,
    private val onLog: (String) -> Unit = {},
    private val onRequestReattach: (
        sessionId: String,
        channelId: String,
        remoteModuleId: String
    ) -> ReattachDispatchOutcome,
    private val onIceRestart: (sessionId: String, remoteModuleId: String) -> Boolean,
    /**
     * Action gate for host ICE restart dispatch (ADR-0032 INV-REC-010).
     * When false, [resolveMediaActionOwner] defers until transport, discovery and peer
     * signaling allow initiation. MUST NOT require [EdgeReachabilitySnapshot.mediaRouteConnected].
     */
    private val canDispatchRecoveryMediaAction: (sessionId: String, remoteModuleId: String) -> Boolean =
        { _, _ -> true },
    /**
     * Negotiation Stabilization Gate (INV-NEG-006): execution admission probe.
     * Settling checked before signalingState. Injected by Coordinator;
     * Recovery MUST NOT read MeshEngine settling directly.
     * Step A-1: [IceRestartGateProbe.blockReason] splits ANSWERER_SETTLING vs SIGNALING_NOT_STABLE.
     */
    private val probeIceRestartGate: (sessionId: String, remoteModuleId: String) -> IceRestartGateProbe =
        { _, _ -> IceRestartGateProbe(executable = true) },
    /**
     * INV-NEG-015 / INV-NEG-020: when Recovery admits a negotiation-deferred ICE restart intent,
     * Coordinator must establish capability observation baseline=false and immediately recompute
     * (DEFER_ADMISSION seam). [bindAdmissionSeq] MUST be invoked with the baseline observation
     * seq **before** any rising-edge drain so INV-NEG-019 freshness holds.
     */
    private val onNegotiationGateDeferred: (
        sessionId: String,
        remoteModuleId: String,
        bindAdmissionSeq: (Long) -> Unit
    ) -> Unit = { _, _, bindAdmissionSeq -> bindAdmissionSeq(0L) },
    /**
     * Probe current ICE connectedness after ACCEPTED / ICE restart (#83).
     * Coordinator wires qosMonitor; tests inject to cover already-CONNECTED soak gap.
     */
    private val isIceConnected: (sessionId: String, remoteModuleId: String) -> Boolean = { _, _ -> false },
    private val onRecoveryStateChanged: (sessionId: String) -> Unit = {},
    /**
     * Observe-only hook for attempt lineage snapshots (ADR-0022 completion causality).
     * [supersededFromAttempt] is set only on SUPERSEDE pathways.
     */
    private val onAttemptLineageObservation: (
        sessionId: String,
        remoteModuleId: String,
        trigger: String,
        supersededFromAttempt: Long?
    ) -> Unit = { _, _, _, _ -> },
    /** ADR-0035 PR2: bounded recovery-offer delivery retry budget. */
    private val maxDeliveryAttempts: Int = 3,
    private val deliveryRetryIntervalMs: Long = 3_000L,
    private val deliveryRetryMinGapMs: Long = 500L,
    /**
     * Coordinator executes same-lineage recovery offer dispatch (deliveryAttempt++ only).
     */
    private val onDispatchRecoveryOffer: (
        sessionId: String,
        remoteModuleId: String,
        offerLineageId: String,
        deliveryAttemptId: Long
    ) -> Boolean = { _, _, _, _ -> false },
    /**
     * PR3-1: admission projection for recovery-offer dispatch (initial + retry).
     * Coordinator wires peer-edge inbound evidence; tests default to DISPATCH_NOW.
     */
    private val evaluateRecoveryAdmission: (
        sessionId: String,
        remoteModuleId: String
    ) -> PeerSignalingReachabilityProjection = { _, _ -> defaultRecoveryAdmissionProjection() },
    private val onRecoveryOfferDeliveryExhausted: (
        sessionId: String,
        remoteModuleId: String,
        offerLineageId: String
    ) -> Unit = { _, _, _ -> },
    /** ADR-0022 E.18.2: membership epoch probe; Coordinator wires [WiredMembershipEpochProbe]. */
    private val membershipEpochProbe: MembershipEpochConvergenceProbe =
        DefaultOpenMembershipAuthoritySentinel,
    /** ADR-0036 RCA-6: membership resync in-flight per (channelId, recoveryEpisodeId). */
    private val isMembershipConvergenceInFlight: (channelId: String, obligationGeneration: Long) -> Boolean =
        { _, _ -> false },
) {
    private val edges = ConcurrentHashMap<ConferenceEdgeKey, EdgeRecoveryRecord>()
    private val debounceTimers = ConcurrentHashMap<ConferenceEdgeKey, ScheduledFuture<*>>()
    private val watchdogTimers = ConcurrentHashMap<ConferenceEdgeKey, ScheduledFuture<*>>()
    private val deadlineTimers = ConcurrentHashMap<ConferenceEdgeKey, ScheduledFuture<*>>()
    private val cancelledSessions = ConcurrentHashMap<String, Long>()
    private val cancelledChannels = ConcurrentHashMap<String, Long>()
    private val pendingTransportNonce = ConcurrentHashMap<ConferenceEdgeKey, String>()
    private var attemptSeq = 0L
    /** Commit Seam Trace: monotonic ICE-restart deferred intent ids (R1, R2, 鈥?. */
    private val iceRestartIntentSeq = AtomicLong(0L)
    /** G-R28-M-5: suppress duplicate post-terminal facts (e.g. repeated HELLO). */
    private data class TerminalReevaluateKey(val attemptId: Long, val trigger: RecoveryReevaluateTrigger)
    private val terminalReevaluateDedup = ConcurrentHashMap<ConferenceEdgeKey, TerminalReevaluateKey>()

    private val recoveryOfferDeliveryPolicy = RecoveryOfferDeliveryPolicy(
        localModuleId = localModuleId,
        maxDeliveryAttempts = maxDeliveryAttempts,
        deliveryRetryIntervalMs = deliveryRetryIntervalMs,
        deliveryRetryMinGapMs = deliveryRetryMinGapMs,
        clock = clock,
        scheduler = scheduler,
        onLog = onLog,
        onDispatchRecoveryOffer = onDispatchRecoveryOffer,
        canDispatchRecoverySignal = canDispatchRecoveryMediaAction,
        evaluateRecoveryAdmission = evaluateRecoveryAdmission,
        onDeliveryExhausted = onRecoveryOfferDeliveryExhausted
    ).also { policy ->
        policy.bindEdgesLookup { edges[it] }
    }

    /**
     * ADR-0022 搂E.16.1 Slice-1: DeferredIntentAuthority owns supersede legality / facts.
     * Does not own drain algorithm, delivery, or CompletionPolicy.
     */
    private val deferredIntentAuthority = DeferredIntentAuthority(
        onLog = onLog,
        clock = clock,
        onReleaseFence = { sessionId, remoteModuleId, intentId, reason ->
            if (Pr52cDebugInjection.fencedIntentId(sessionId, remoteModuleId) == intentId) {
                Pr52cDebugInjection.clearValidationFence(sessionId, remoteModuleId)
                onLog(
                    "DEFERRED_INTENT_VALIDATION_FENCE_CLEARED session=$sessionId " +
                        "remote=$remoteModuleId intentId=$intentId reason=$reason"
                )
            }
        },
        onNegotiationCloseRequest = { sessionId, remoteModuleId, intentId, terminalHint, source, cause ->
            onDeferredIntentNegotiationCloseRequest(
                sessionId = sessionId,
                remoteModuleId = remoteModuleId,
                intentId = intentId,
                terminalHint = terminalHint,
                source = source,
                cause = cause
            )
        }
    )

    private val completionMutationHost = object : RecoveryCompletionPolicy.MutationHost {
        override fun currentRecord(key: ConferenceEdgeKey) = edges[key]
        override fun clock(): Long = this@ConferenceEdgeRecoveryController.clock()
        override fun log(message: String) = onLog(message)
        override fun cancelDebounce(key: ConferenceEdgeKey) =
            this@ConferenceEdgeRecoveryController.cancelDebounce(key)
        override fun cancelWatchdog(key: ConferenceEdgeKey) =
            this@ConferenceEdgeRecoveryController.cancelWatchdog(key)
        override fun cancelDeadline(key: ConferenceEdgeKey) =
            this@ConferenceEdgeRecoveryController.cancelDeadline(key)
        override fun logPhaseTransition(
            record: EdgeRecoveryRecord,
            oldPhase: EdgeRecoveryPhase,
            newPhase: EdgeRecoveryPhase,
            reason: String
        ) = this@ConferenceEdgeRecoveryController.logPhaseTransition(record, oldPhase, newPhase, reason)
        override fun expireDeferredIceRestartIntent(record: EdgeRecoveryRecord, reason: String) =
            this@ConferenceEdgeRecoveryController.expireDeferredIceRestartIntent(record, reason)
        override fun notifyAttemptLineageObservation(record: EdgeRecoveryRecord, reason: String) =
            this@ConferenceEdgeRecoveryController.notifyAttemptLineageObservation(record, reason)
        override fun notifyChanged(sessionId: String) =
            this@ConferenceEdgeRecoveryController.notifyChanged(sessionId)
        override fun logObligationCloseRequested(
            record: EdgeRecoveryRecord,
            reason: ObligationCloseReason,
            closeEvidence: String?
        ) = this@ConferenceEdgeRecoveryController.logObligationCloseRequested(record, reason, closeEvidence)
    }

    init {
        RecoveryAttemptOwner.bindLogSink(onLog)
        onLog(
            "RECOVERY_BUILD_IDENTITY authorityReleaseIntent=true INV-DI-001_enabled=true " +
                "buildTag=PR5-3_Grill_R2 contract=releaseIntent"
        )
        RecoveryDeliveryFact.bindIngressAbsentHandler { identity, sessionId ->
            val sid = sessionId?.takeIf { it.isNotBlank() } ?: return@bindIngressAbsentHandler
            val record = edges[ConferenceEdgeKey(sid, identity.to)] ?: return@bindIngressAbsentHandler
            recoveryOfferDeliveryPolicy.onRemoteIngressAbsent(record, identity, sessionId)
        }
    }

    /** ADR-0035 PR2: outbound recovery offer entered DELIVERY_PENDING (Coordinator fact). */
    fun onRecoveryOfferDeliveryPending(
        sessionId: String,
        remoteModuleId: String,
        identity: RecoveryDeliveryFact.Identity
    ) {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return
        recoveryOfferDeliveryPolicy.onOutboundDeliveryPending(record, identity, sessionId)
    }

    /** ADR-0035 PR2/PR4: matching ACK accepted for current lineage. */
    fun onRecoveryOfferDeliveryConfirmed(
        sessionId: String,
        remoteModuleId: String,
        offerLineageId: String,
        handlerOutcome: RecoveryHandlerOutcome = RecoveryHandlerOutcome.ACCEPTED
    ) {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return
        if (record.recoveryOfferDeliveryPhase == RecoveryOfferDeliveryPhase.EXHAUSTED) return
        record.deliveryConfirmedOutcome = handlerOutcome
        recoveryOfferDeliveryPolicy.onDeliveryConfirmed(record, offerLineageId)
        onLog(
            "RECOVERY_REEVALUATE_STARTED session=$sessionId edge=$remoteModuleId " +
                "trigger=DELIVERY_CONFIRMED deliveryConfirmedOutcome=$handlerOutcome " +
                "offerLineageId=$offerLineageId"
        )
    }

    fun isRecoveryOfferDeliveryExhausted(sessionId: String, remoteModuleId: String): Boolean {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        return record.recoveryOfferDeliveryPhase == RecoveryOfferDeliveryPhase.EXHAUSTED
    }

    internal fun evaluateRecoveryOfferDeliveryRetryForTest(
        sessionId: String,
        remoteModuleId: String,
        trigger: String
    ) {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return
        recoveryOfferDeliveryPolicy.evaluateDeliveryRetryForTest(record, trigger)
    }

    fun factsForSession(sessionId: String): EdgeRecoveryFacts {
        val sessionEdges = edges.values.filter { it.key.sessionId == sessionId }
        val recovering = sessionEdges
            .filter { it.phase.isActivelyRecovering() }
            .map { it.key.remoteModuleId }
            .toSet()
        val failed = sessionEdges
            .filter { it.phase.isFailedMediaRecovery() }
            .map { it.key.remoteModuleId }
            .toSet()
        // ADR-0030: failed-media residency (e.g. FAILED_MEDIA_RECOVERY) == mediaUnavailable(P).
        return EdgeRecoveryFacts(
            recoveringRemoteModuleIds = recovering,
            anyRecovering = recovering.isNotEmpty(),
            failedRemoteModuleIds = failed,
            anyFailedMediaRecovery = failed.isNotEmpty(),
            mediaUnavailableRemoteModuleIds = failed
        )
    }

    /** Per-peer ADR-0030 fact: failed-media residency, not active recovery attempt. */
    fun isMediaUnavailable(sessionId: String, remoteModuleId: String): Boolean {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        return record.phase.isFailedMediaRecovery()
    }

    fun isAnyEdgeRecovering(sessionId: String): Boolean = factsForSession(sessionId).anyRecovering

    /** True while edge (sessionId, remoteModuleId) is in an active recovery ownership window (R26). */
    fun isEdgeRecovering(sessionId: String, remoteModuleId: String): Boolean {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        return record.phase.isActivelyRecovering()
    }

    /** Whether current attempt crossed the control-plane boundary (ADR-0022 R28-E). */
    fun isControlPlaneStarted(sessionId: String, remoteModuleId: String): Boolean {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        return record.controlPlaneStarted()
    }

    /**
     * Recovery Edge Obligation OPEN (ADR-0022 R28-H).
     * OPEN until exclusive close stamp (including [ObligationCloseReason.OBLIGATION_DEADLINE]).
     */
    fun edgeObligationOpen(sessionId: String, remoteModuleId: String): Boolean {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        return record.edgeObligationOpen()
    }

    /** True after controller stamped an exclusive close reason (ADR-0022 R28-H). */
    fun edgeObligationClosed(sessionId: String, remoteModuleId: String): Boolean {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        return record.obligationClosedAtMs != null
    }

    fun obligationDeadlineAt(sessionId: String, remoteModuleId: String): Long? =
        edges[ConferenceEdgeKey(sessionId, remoteModuleId)]?.obligationDeadlineAtMs

    fun obligationCloseReason(sessionId: String, remoteModuleId: String): ObligationCloseReason? =
        edges[ConferenceEdgeKey(sessionId, remoteModuleId)]?.obligationCloseReason

    fun hasPendingCompletionDecision(sessionId: String, remoteModuleId: String): Boolean =
        edges[ConferenceEdgeKey(sessionId, remoteModuleId)]?.hasPendingCompletionDecision ?: false

    /**
     * Appendix C-3.2 (C-12): deferred attempt with [WakeupBinding] matching [trigger].
     * Used by coordinator materiality gate to force RECOVERY_REEVALUATE.
     */
    fun hasDeferredWakeupForTrigger(
        sessionId: String,
        remoteModuleId: String,
        trigger: RecoveryReevaluateTrigger
    ): Boolean {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        if (!record.edgeObligationOpen() || !hasDeferredMediaAction(record)) return false
        val binding = record.wakeupBinding ?: return false
        return binding.matchesTrigger(trigger, sessionId, remoteModuleId)
    }

    /** Forensics snapshot for lifecycle trace (observe only). */
    fun pendingForensics(sessionId: String): List<String> {
        val actions = mutableListOf<String>()
        debounceTimers.keys.filter { it.sessionId == sessionId }.forEach { key ->
            actions.add("DEBOUNCE:${key.remoteModuleId}")
        }
        watchdogTimers.keys.filter { it.sessionId == sessionId }.forEach { key ->
            actions.add("WATCHDOG:${key.remoteModuleId}")
        }
        deadlineTimers.keys.filter { it.sessionId == sessionId }.forEach { key ->
            actions.add("DEADLINE:${key.remoteModuleId}")
        }
        edges.values.filter { it.key.sessionId == sessionId }.forEach { record ->
            if (record.hasPendingCompletionDecision) {
                actions.add("PENDING_COMPLETION:${record.key.remoteModuleId}")
            }
            if (record.edgeObligationOpen()) {
                actions.add("OBLIGATION_OPEN:${record.key.remoteModuleId}")
            }
        }
        if (cancelledSessions.containsKey(sessionId)) {
            actions.add("SESSION_CANCELLED")
        }
        return actions
    }

    fun edgePhaseSummary(sessionId: String): String =
        edges.values
            .filter { it.key.sessionId == sessionId }
            .joinToString(";") { record ->
                "${record.key.remoteModuleId}:${record.phase}@a${record.recoveryAttemptId}"
            }

    /** Read-only attempt lineage for ownership observation (no mutation). */
    fun attemptLineageObservation(sessionId: String, remoteModuleId: String): EdgeAttemptLineageRaw? {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return null
        return EdgeAttemptLineageRaw(
            attemptId = record.recoveryAttemptId,
            attemptStartedAtMs = record.recoveryStartedAtMs,
            phase = record.phase,
            mediaRestored = record.mediaRestored,
            obligationOpen = record.edgeObligationOpen(),
            pendingCompletion = record.hasPendingCompletionDecision,
            obligationGeneration = record.obligationGeneration
        )
    }

    /** ADR-0037 Phase 3.1/3.2: read-only snapshot for negotiation observation. */
    fun negotiationObservationContext(
        sessionId: String,
        remoteModuleId: String
    ): RecoveryNegotiationObservation.EdgeObservationContext? {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return null
        val coordinatorOwner = RecoveryNegotiationAuthority.bootstrapCoordinatorOwner(
            localModuleId = localModuleId,
            remoteModuleId = remoteModuleId,
            initiatesReattach = record.initiatesReattach,
            recoveryViaInboundReattach = record.recoveryViaInboundReattach
        )
        return RecoveryNegotiationObservation.EdgeObservationContext(
            sessionId = sessionId,
            edgeModuleId = remoteModuleId,
            episodeId = record.recoveryAttemptId,
            obligationGen = record.obligationGeneration,
            intentId = record.iceRestartIntentId,
            mediaActionOwnerLabel = record.mediaActionOwner.logLabel(),
            deferredReason = record.deferredReason?.name,
            existingTransactionOwnerModuleId = record.canonicalNegotiationOwnerModuleId,
            recoveryCoordinatorOwnerModuleId = coordinatorOwner
        )
    }

    /** ADR-0037 Phase 3.2: canonical owner for outbound recovery envelope stamp (A). */
    fun negotiationOwnerModuleId(sessionId: String, remoteModuleId: String): String? {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return null
        return ensureCanonicalNegotiationOwner(record, "OUTBOUND_OFFER_STAMP")
    }

    fun validateInboundNegotiationOwner(
        sessionId: String,
        remoteModuleId: String,
        wireOwnerModuleId: String?,
        recoveryEpisodeId: Long
    ): RecoveryNegotiationAuthority.WireOwnerResult? {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return null
        if (recoveryEpisodeId > 0L && record.recoveryAttemptId != recoveryEpisodeId) {
            return RecoveryNegotiationAuthority.WireOwnerResult(
                validation = RecoveryNegotiationAuthority.WireOwnerValidation.CONFLICT,
                canonicalOwner = record.canonicalNegotiationOwnerModuleId
                    ?: RecoveryNegotiationAuthority.resolveOwner(ownerElectionInput(record)).negotiationOwnerModuleId,
                wireOwner = wireOwnerModuleId,
                conflictOwner = wireOwnerModuleId
            )
        }
        return RecoveryNegotiationAuthority.validateWireOwner(
            ownerElectionInput(record),
            wireOwnerModuleId
        )
    }

    fun adoptInboundNegotiationOwner(
        sessionId: String,
        remoteModuleId: String,
        ownerModuleId: String,
        trigger: String
    ) {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return
        if (record.canonicalNegotiationOwnerModuleId == null) {
            record.canonicalNegotiationOwnerModuleId = ownerModuleId
            onLog(
                "RECOVERY_NEGOTIATION_OWNER_ADOPTED session=$sessionId edge=$remoteModuleId " +
                    "owner=$ownerModuleId trigger=$trigger"
            )
        }
        observeNegotiationOwner(record, trigger)
    }

    fun onNegotiationGlareAcceptRemote(sessionId: String, remoteModuleId: String, reason: String) {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return
        if (!hasDeferredMediaAction(record) && record.iceRestartIntentId == null) return
        expireDeferredIceRestartIntent(record, "GLARE:$reason")
    }

    private fun ownerElectionInput(record: EdgeRecoveryRecord): RecoveryNegotiationAuthority.OwnerElectionInput {
        val coordinatorOwner = RecoveryNegotiationAuthority.bootstrapCoordinatorOwner(
            localModuleId = localModuleId,
            remoteModuleId = record.key.remoteModuleId,
            initiatesReattach = record.initiatesReattach,
            recoveryViaInboundReattach = record.recoveryViaInboundReattach
        )
        return RecoveryNegotiationAuthority.OwnerElectionInput(
            key = RecoveryNegotiationAuthority.RecoveryNegotiationKey(
                sessionId = record.key.sessionId,
                edgeModuleId = record.key.remoteModuleId,
                recoveryEpisodeId = record.recoveryAttemptId
            ),
            localModuleId = localModuleId,
            remoteModuleId = record.key.remoteModuleId,
            existingTransactionOwnerModuleId = record.canonicalNegotiationOwnerModuleId,
            recoveryCoordinatorOwnerModuleId = coordinatorOwner
        )
    }

    private fun ensureCanonicalNegotiationOwner(record: EdgeRecoveryRecord, trigger: String): String {
        if (record.canonicalNegotiationOwnerModuleId == null) {
            val resolution = RecoveryNegotiationAuthority.resolveOwner(ownerElectionInput(record))
            record.canonicalNegotiationOwnerModuleId = resolution.negotiationOwnerModuleId
            onLog(
                "RECOVERY_NEGOTIATION_OWNER_BOOTSTRAP session=${record.key.sessionId} " +
                    "edge=${record.key.remoteModuleId} episodeId=${record.recoveryAttemptId} " +
                    "owner=${resolution.negotiationOwnerModuleId} rule=${resolution.rule.name} trigger=$trigger"
            )
        }
        observeNegotiationOwner(record, trigger)
        return record.canonicalNegotiationOwnerModuleId!!
    }

    private fun observeNegotiationOwner(record: EdgeRecoveryRecord, trigger: String) {
        negotiationObservationContext(record.key.sessionId, record.key.remoteModuleId)?.let { ctx ->
            RecoveryNegotiationObservation.emitOwnerResolvedFromContext(ctx, localModuleId, trigger)
        }
    }

    fun obligationGeneration(sessionId: String, remoteModuleId: String): Long? =
        edges[ConferenceEdgeKey(sessionId, remoteModuleId)]?.obligationGeneration

    /** Lineage material for outbound REATTACH wire encoding (ADR-0022 Appendix D). */
    fun reattachDispatchLineage(sessionId: String, remoteModuleId: String): ReattachDispatchLineage? {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return null
        return ReattachDispatchLineage(
            attemptId = record.recoveryAttemptId,
            obligationGeneration = record.obligationGeneration
        )
    }

    /** Coordinator registers envelope nonce after transport send, before dispatch outcome apply. */
    fun registerReattachTransportNonce(sessionId: String, remoteModuleId: String, nonce: String) {
        pendingTransportNonce[ConferenceEdgeKey(sessionId, remoteModuleId)] = nonce
    }

    fun evaluateInboundReattachLineage(
        sessionId: String,
        remoteModuleId: String,
        senderAttemptId: Long,
        senderObligationGeneration: Long,
        inboundDelivery: InboundReattachDeliveryIdentity? = null
    ): InboundReattachLineageVerdict {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return InboundReattachLineageVerdict.ACCEPT
        // INV-PR52c-004: OBLIGATION_DEADLINE rejects late inbound; RECOVERED does not quarantine
        // opposite-direction delivery (INV-PR52c-003/005).
        if (record.obligationClosedAtMs != null &&
            record.obligationCloseReason == ObligationCloseReason.OBLIGATION_DEADLINE
        ) {
            return InboundReattachLineageVerdict.STALE_OBLIGATION_GENERATION
        }
        if (inboundDelivery != null &&
            (inboundDelivery.from != remoteModuleId || inboundDelivery.to != localModuleId)
        ) {
            return InboundReattachLineageVerdict.STALE_OBLIGATION_GENERATION
        }
        // INV-PR52c-007/008: opposite-direction inbound is not rejected solely for attempt mismatch,
        // local RECOVERED closure, or receiver.currentObligationGeneration. Orphan same-direction
        // stale delivery is enforced on outbound dispatch / authority ACK pending match.
        return InboundReattachLineageVerdict.ACCEPT
    }

    fun onRecoveryReattachReceipt(
        sessionId: String,
        remoteModuleId: String,
        nonce: String,
        attemptId: Long,
        obligationGeneration: Long
    ): Boolean {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: return false
        if (record.obligationClosedAtMs != null) return false
        if (obligationGeneration > 0L &&
            record.obligationGeneration > 0L &&
            obligationGeneration != record.obligationGeneration
        ) {
            onLog(
                "RECOVERY_REATTACH_RECEIPT_REJECTED session=$sessionId remote=$remoteModuleId " +
                    "reason=stale_obligation_generation receiptGen=$obligationGeneration " +
                    "currentGen=${record.obligationGeneration} attempt=$attemptId"
            )
            return false
        }
        if (attemptId > 0L &&
            record.recoveryAttemptId > 0L &&
            attemptId != record.recoveryAttemptId
        ) {
            if (record.reattachNonce == nonce &&
                record.reattachDeliveryState == ReattachDeliveryState.REMOTE_RECEIPT_ACKED
            ) {
                return true
            }
            onLog(
                "RECOVERY_REATTACH_RECEIPT_REJECTED session=$sessionId remote=$remoteModuleId " +
                    "reason=stale_attempt receiptAttempt=$attemptId currentAttempt=${record.recoveryAttemptId}"
            )
            return false
        }
        if (record.reattachNonce == nonce &&
            record.reattachDeliveryState == ReattachDeliveryState.REMOTE_RECEIPT_ACKED
        ) {
            return true
        }
        record.reattachDeliveryState = ReattachDeliveryState.REMOTE_RECEIPT_ACKED
        onLog(
            "RECOVERY_REATTACH_RECEIPT session=$sessionId remote=$remoteModuleId " +
                "nonce=$nonce attempt=$attemptId obligationGen=$obligationGeneration " +
                "deliveryState=REMOTE_RECEIPT_ACKED controlPlaneStarted=${record.controlPlaneStarted()}"
        )
        notifyChanged(sessionId)
        return true
    }

    fun onRecoveryReattachInboundReceived(
        sessionId: String,
        channelId: String,
        remoteModuleId: String,
        senderAttemptId: Long,
        senderObligationGeneration: Long,
        nonce: String
    ): InboundReattachLineageVerdict {
        val verdict = evaluateInboundReattachLineage(
            sessionId,
            remoteModuleId,
            senderAttemptId,
            senderObligationGeneration
        )
        if (verdict != InboundReattachLineageVerdict.ACCEPT) {
            onLog(
                "RECOVERY_REATTACH_INBOUND_REJECTED session=$sessionId remote=$remoteModuleId " +
                    "reason=$verdict senderAttempt=$senderAttemptId senderObligationGen=$senderObligationGeneration " +
                    "nonce=$nonce"
            )
            return verdict
        }
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: upsertEdge(
            key,
            channelId,
            EdgeRecoveryPhase.RECOVERY_PENDING,
            initiatesReattach = false,
            newAttempt = true,
            attemptOpenTrigger = "INBOUND_REATTACH"
        )
        record.channelId = channelId
        record.reattachDeliveryState = ReattachDeliveryState.RECEIVED
        onLog(
            "RECOVERY_REATTACH_INBOUND session=$sessionId remote=$remoteModuleId " +
                "deliveryState=RECEIVED senderAttempt=$senderAttemptId " +
                "senderObligationGen=$senderObligationGeneration nonce=$nonce"
        )
        notifyChanged(sessionId)
        return InboundReattachLineageVerdict.ACCEPT
    }

    fun onRecoveryReattachInboundDeferred(
        sessionId: String,
        channelId: String,
        remoteModuleId: String,
        reason: DeferredReason,
        trigger: String = "INBOUND_REATTACH"
    ) {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: upsertEdge(
            key,
            channelId,
            EdgeRecoveryPhase.RECOVERY_PENDING,
            initiatesReattach = false,
            newAttempt = false,
            attemptOpenTrigger = trigger
        )
        record.channelId = channelId
        record.reattachDeliveryState = ReattachDeliveryState.DEFERRED
        recordMediaActionDeferred(
            record = record,
            owner = MediaActionOwner.HOST_RESTART,
            reason = reason,
            wakeupBinding = WakeupBinding(
                sourceType = WakeupSourceType.ROUTE_CONVERGED,
                sourceKey = edgeWakeupKey(sessionId, remoteModuleId)
            ),
            trigger = trigger
        )
        onLog(
            "RECOVERY_REATTACH_INBOUND_DEFERRED session=$sessionId remote=$remoteModuleId " +
                "deliveryState=DEFERRED deferredReason=$reason trigger=$trigger " +
                "attempt=${record.recoveryAttemptId} obligationGen=${record.obligationGeneration}"
        )
        notifyChanged(sessionId)
    }

    private fun notifyAttemptLineageObservation(
        record: EdgeRecoveryRecord,
        trigger: String,
        supersededFromAttempt: Long? = null
    ) {
        onAttemptLineageObservation(
            record.key.sessionId,
            record.key.remoteModuleId,
            trigger,
            supersededFromAttempt
        )
    }

    private fun formatRecoveryAttemptOpenedLog(
        sessionId: String,
        remoteModuleId: String,
        attemptId: Long,
        initiator: String,
        policy: String,
        startedAt: Long,
        supersededFromAttempt: Long?,
        reason: String,
        previousAttempt: Long?,
        previousPhase: EdgeRecoveryPhase?,
        obligationOpen: Boolean,
        obligationGeneration: Long,
        pathway: String
    ): String =
        "RECOVERY_ATTEMPT_OPENED session=$sessionId remote=$remoteModuleId " +
            "attemptId=$attemptId initiator=$initiator policy=$policy startedAt=$startedAt " +
            "supersededFromAttempt=${supersededFromAttempt ?: "NONE"} reason=$reason " +
            "newAttempt=$attemptId previousAttempt=${previousAttempt ?: "NONE"} " +
            "previousPhase=${previousPhase ?: "NONE"} previousObligationOpen=$obligationOpen " +
            "obligationGen=$obligationGeneration pathway=$pathway"

    private fun logPhaseTransition(
        record: EdgeRecoveryRecord,
        oldPhase: EdgeRecoveryPhase?,
        newPhase: EdgeRecoveryPhase,
        trigger: String
    ) {
        if (oldPhase == newPhase) return
        onLog(
            "RECOVERY_TRANSITION session=${record.key.sessionId} remote=${record.key.remoteModuleId} " +
                "old=${oldPhase ?: "NONE"} new=$newPhase trigger=$trigger attempt=${record.recoveryAttemptId} " +
                "obligationGen=${record.obligationGeneration} " +
                "obligationOpen=${record.edgeObligationOpen()} " +
                "pendingCompletion=${record.hasPendingCompletionDecision}"
        )
        RecoveryAttemptOwner.reconcileFromFacts(record, "PHASE:$trigger")
    }

    /**
     * True when a new ICE failure must start a fresh obligation episode (P1).
     * Active recovery / failed-media residency continues the current episode.
     */
    private fun needsNewObligationEpisode(record: EdgeRecoveryRecord?): Boolean {
        if (record == null) return false
        if (record.phase == EdgeRecoveryPhase.RECOVERED) return true
        if (record.obligationClosedAtMs != null) return true
        return !record.edgeObligationOpen() &&
            !record.phase.isActivelyRecovering() &&
            !record.phase.isFailedMediaRecovery()
    }

    /**
     * Opens a new recovery obligation episode after a healthy edge failure (P1).
     * Does not reuse closed recovery identity or prior attempt context.
     */
    private fun openNewRecoveryObligation(
        key: ConferenceEdgeKey,
        channelId: String,
        phase: EdgeRecoveryPhase,
        initiatesReattach: Boolean,
        trigger: String
    ): EdgeRecoveryRecord {
        cancelDebounce(key)
        cancelWatchdog(key)
        cancelDeadline(key)
        val existing = edges[key]
        val now = clock()
        val newGeneration = (existing?.obligationGeneration ?: 0L) + 1L
        val previousAttempt = existing?.recoveryAttemptId
        val previousPhase = existing?.phase
        val record = EdgeRecoveryRecord(
            key = key,
            phase = phase,
            channelId = channelId.ifBlank { existing?.channelId ?: "" },
            recoveryAttemptId = ++attemptSeq,
            recoveryStartedAtMs = now,
            initiatesReattach = initiatesReattach,
            obligationGeneration = newGeneration,
            obligationOpenedAtMs = now,
            obligationDeadlineAtMs = null,
            obligationClosedAtMs = null,
            obligationCloseReason = null,
            hasPendingCompletionDecision = false
        )
        edges[key] = record
        ensureCanonicalNegotiationOwner(record, "OBLIGATION_OPENED:$trigger")
        onLog(
            "RECOVERY_OBLIGATION_OPENED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "obligationGen=$newGeneration attempt=${record.recoveryAttemptId} trigger=$trigger"
        )
        onLog(
            formatRecoveryAttemptOpenedLog(
                sessionId = key.sessionId,
                remoteModuleId = key.remoteModuleId,
                attemptId = record.recoveryAttemptId,
                initiator = resolveRecoveryInitiator(initiatesReattach),
                policy = resolveRecoveryPolicy(initiatesReattach),
                startedAt = record.recoveryStartedAtMs,
                supersededFromAttempt = null,
                reason = trigger,
                previousAttempt = previousAttempt,
                previousPhase = previousPhase,
                obligationOpen = true,
                obligationGeneration = newGeneration,
                pathway = "NEW_OBLIGATION_EPISODE"
            )
        )
        logPhaseTransition(record, previousPhase, phase, trigger)
        return record
    }

    private fun assignMediaActionOwner(
        record: EdgeRecoveryRecord,
        owner: MediaActionOwner,
        mediaActionOwnerModuleId: String = localModuleId,
        parentAttempt: Long? = null,
        supersededByModule: String? = null
    ) {
        if (record.mediaActionOwner.isAssigned() && owner != MediaActionOwner.ABORTED) {
            when {
                record.mediaActionOwner == owner && hasDeferredMediaAction(record) -> Unit
                record.mediaActionOwner == owner -> return
                else -> {
                    onLog(
                        "RECOVERY_MEDIA_OWNER_REJECTED session=${record.key.sessionId} " +
                            "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                            "existing=${record.mediaActionOwner.logLabel()} requested=${owner.logLabel()}"
                    )
                    return
                }
            }
        }
        record.mediaActionOwner = owner
        when (owner) {
            MediaActionOwner.ABORTED -> record.mediaActionDisposition = MediaActionDisposition.ABORTED
            MediaActionOwner.HOST_RESTART,
            MediaActionOwner.PARTICIPANT_REATTACH -> {
                record.mediaActionDisposition = MediaActionDisposition.ACTIVE
                record.deferredReason = null
                record.wakeupBinding = null
            }
            else -> Unit
        }
        onLog(
            "RECOVERY_MEDIA_OWNER_ASSIGNED session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                "owner=${owner.logLabel()} recoveryOwnerModuleId=$localModuleId " +
                "mediaActionOwnerModuleId=$mediaActionOwnerModuleId " +
                "parentAttempt=${parentAttempt ?: "NONE"} " +
                "supersededByModule=${supersededByModule ?: "NONE"}"
        )
    }

    private fun logHandoffToReattach(
        record: EdgeRecoveryRecord,
        supersededByModule: String,
        attempt: Long
    ) {
        onLog(
            "RECOVERY_HANDOFF_TO_REATTACH session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} attempt=$attempt " +
                "supersededByModule=$supersededByModule"
        )
        if (!record.mediaActionOwner.isAssigned()) {
            assignMediaActionOwner(
                record = record,
                owner = MediaActionOwner.PARTICIPANT_REATTACH,
                mediaActionOwnerModuleId = supersededByModule,
                parentAttempt = attempt,
                supersededByModule = supersededByModule
            )
        }
    }

    private fun hasParticipantHandoffPending(record: EdgeRecoveryRecord): Boolean =
        record.mediaActionOwner == MediaActionOwner.PARTICIPANT_REATTACH ||
            record.phase == EdgeRecoveryPhase.REATTACH_REQUESTED ||
            record.phase == EdgeRecoveryPhase.REATTACH_ACCEPTED

    private fun recordMediaActionDeferred(
        record: EdgeRecoveryRecord,
        owner: MediaActionOwner,
        reason: DeferredReason,
        wakeupBinding: WakeupBinding,
        trigger: String,
        mediaActionOwnerModuleId: String = localModuleId
    ) {
        val key = record.key
        val fencedIntentId = record.iceRestartIntentId
        if (
            fencedIntentId != null &&
            record.deferredReason == DeferredReason.NEGOTIATION_SETTLING &&
            Pr52cDebugInjection.shouldSuppressProductionDeferredDrain(
                key.sessionId,
                key.remoteModuleId,
                fencedIntentId,
                trigger
            )
        ) {
            onLog(
                "DEFERRED_INTENT_VALIDATION_FENCE session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "intentId=$fencedIntentId seam=$trigger action=suppress_production_media_defer"
            )
            return
        }
        record.mediaActionOwner = owner
        record.mediaActionDisposition = MediaActionDisposition.DEFERRED
        record.deferredReason = reason
        record.wakeupBinding = wakeupBinding
        onLog(
            "RECOVERY_MEDIA_OWNER_ASSIGNED session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                "owner=${owner.logLabel()} recoveryOwnerModuleId=$localModuleId " +
                "mediaActionOwnerModuleId=$mediaActionOwnerModuleId " +
                "parentAttempt=NONE supersededByModule=NONE"
        )
        onLog(
            "RECOVERY_MEDIA_ACTION_DEFERRED session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                "owner=${owner.logLabel()} disposition=DEFERRED " +
                "deferredReason=$reason trigger=$trigger " +
                "wakeupBinding=${wakeupBinding.logLabel()}"
        )
        onLog(
            "RECOVERY_WAKEUP_ARMED session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} trigger=${wakeupBinding.sourceType} " +
                "wakeupBinding=${wakeupBinding.logLabel()} deferredReason=$reason"
        )
        observeNegotiationOwner(record, trigger)
        // RNA-5.1 / Gate 3C-C: negotiation intent only for committed NEGOTIATION_SETTLING slots.
        if (reason == DeferredReason.NEGOTIATION_SETTLING && record.iceRestartIntentId != null) {
            negotiationObservationContext(record.key.sessionId, record.key.remoteModuleId)?.let { ctx ->
                RecoveryNegotiationObservation.emitIntentFromContext(
                    ctx,
                    localModuleId,
                    RecoveryNegotiationObservation.IntentState.DEFERRED,
                    reason.name
                )
            }
        }
    }

    private fun clearDeferralFields(record: EdgeRecoveryRecord) {
        record.mediaActionDisposition = MediaActionDisposition.UNASSIGNED
        record.deferredReason = null
        record.wakeupBinding = null
        record.deferredGateBlockReason = null
        record.deferAdmissionObservationSeq = null
        record.deferredIntentHoldReason = null
        record.deferredIntentDrainRetryCount = 0
    }

    /**
     * INV-DI-001 / Grill R2: release committed intent slot only through Authority.
     */
    private fun releaseDeferredIntentSlot(
        record: EdgeRecoveryRecord,
        reason: String,
        domain: DeferredIntentAuthority.RequestingDomain,
        kind: DeferredIntentAuthority.ReleaseKind,
        expireCause: String? = null
    ) {
        val intentId = record.iceRestartIntentId ?: return
        val result = deferredIntentAuthority.releaseIntent(
            intentId = intentId,
            reason = reason,
            requestingDomain = domain,
            kind = kind,
            expireCause = expireCause
        )
        when (result) {
            is DeferredIntentAuthority.ReleaseResult.Accepted,
            is DeferredIntentAuthority.ReleaseResult.NoAuthorityRecord -> {
                if (record.iceRestartIntentId == intentId) {
                    record.iceRestartIntentId = null
                }
                clearValidationFenceIfArmed(
                    record.key.sessionId,
                    record.key.remoteModuleId,
                    intentId,
                    reason
                )
            }
            is DeferredIntentAuthority.ReleaseResult.Rejected -> {
                onLog(
                    "DEFERRED_INTENT_RELEASE_REJECTED session=${record.key.sessionId} " +
                        "remote=${record.key.remoteModuleId} intentId=$intentId " +
                        "reason=${result.reason} kind=$kind"
                )
            }
        }
    }

    private fun hasDeferredMediaAction(record: EdgeRecoveryRecord): Boolean =
        record.mediaActionDisposition == MediaActionDisposition.DEFERRED &&
            record.mediaActionOwner.isAssigned()

    private fun isNegotiationDeferredIceRestartSlot(record: EdgeRecoveryRecord): Boolean =
        hasDeferredMediaAction(record) &&
            record.deferredReason == DeferredReason.NEGOTIATION_SETTLING &&
            record.iceRestartIntentId != null

    private fun isDeferredIceRestartIntent(record: EdgeRecoveryRecord): Boolean =
        isNegotiationDeferredIceRestartSlot(record)

    /**
     * Commit Seam Trace: deferred ICE-restart intent id awaiting NEGOTIATION_CAN_EXECUTE, if any.
     * Coordinator stamps the same intentId on capability emission for lifecycle join.
     */
    fun pendingIceRestartIntentId(sessionId: String, remoteModuleId: String): String? {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return null
        if (!isDeferredIceRestartIntent(record)) return null
        return record.iceRestartIntentId
    }

    private fun allocateIceRestartIntentId(record: EdgeRecoveryRecord): String {
        val existing = record.iceRestartIntentId
        if (existing != null) return existing
        val id = "R${iceRestartIntentSeq.incrementAndGet()}"
        record.iceRestartIntentId = id
        record.negotiationIntentTerminalEmitted = false
        record.negotiationIntentTerminalState = null
        return id
    }

    private fun clearNegotiationIntentTerminalGuard(record: EdgeRecoveryRecord) {
        record.negotiationIntentTerminalEmitted = false
        record.negotiationIntentTerminalState = null
    }

    private fun resolveNegotiationIntentTerminal(cause: String): String = when {
        cause.startsWith("GLARE:") -> "BLOCKED_BY_GLARE"
        cause.startsWith("SUPERSEDE") || cause.startsWith("ADMIT_SUCCESSOR") -> "SUPERSEDED"
        else -> "EXPIRED"
    }

    private fun resolveNegotiationIntentCloseSource(cause: String): String = when {
        cause.startsWith("GLARE:") -> "GLARE_RESOLVER"
        cause.startsWith("SUPERSEDE") || cause.startsWith("ADMIT_SUCCESSOR") -> "MEDIA_ACTION_SUPERSEDE"
        cause.startsWith("OBLIGATION_CLOSE") -> "OBLIGATION_CLOSE"
        cause.startsWith("DRAIN_") -> "NEGOTIATION_DRAIN"
        else -> "DEFERRED_EXPIRE"
    }

    /**
     * Gate 3C-B / RNA-5.4: bridge DeferredIntentAuthority supersede to negotiation terminal closure.
     * DeferredIntentAuthority emits CLOSE_REQUEST via callback; this is the sole RNA terminal writer.
     */
    private fun onDeferredIntentNegotiationCloseRequest(
        sessionId: String,
        remoteModuleId: String,
        intentId: String,
        terminalHint: String,
        source: String,
        cause: String
    ) {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return
        val terminalReason = when (terminalHint) {
            "SUPERSEDED" -> "SUPERSEDED"
            "BLOCKED_BY_GLARE" -> cause
            else -> cause
        }
        closeNegotiationIntent(
            record = record,
            intentId = intentId,
            terminal = terminalHint,
            reason = terminalReason,
            source = source,
            clearDeferralAfterClose = false
        )
    }

    /**
     * RNA-5 v2 / Gate 3C-A: sole writer for [RECOVERY_NEGOTIATION_INTENT_TERMINAL].
     * Returns true when a new terminal fact was emitted; false when already closed (no-op).
     */
    private fun closeNegotiationIntent(
        record: EdgeRecoveryRecord,
        intentId: String?,
        terminal: String,
        reason: String,
        source: String,
        preTerminalIntentState: RecoveryNegotiationObservation.IntentState? = null,
        markAuthorityExecuted: Boolean = false,
        clearDeferralAfterClose: Boolean = true
    ): Boolean {
        val sessionId = record.key.sessionId
        val remoteModuleId = record.key.remoteModuleId
        val resolvedIntentId = intentId ?: record.iceRestartIntentId

        if (record.negotiationIntentTerminalEmitted) {
            onLog(
                "NEGOTIATION_INTENT_CLOSE_SKIPPED session=$sessionId edge=$remoteModuleId " +
                    "intentId=${resolvedIntentId ?: "NONE"} terminalHint=$terminal source=$source " +
                    "reason=already_closed priorTerminal=${record.negotiationIntentTerminalState ?: "NONE"}"
            )
            return false
        }

        val hasNegotiationLifecycle =
            resolvedIntentId != null ||
                isDeferredIceRestartIntent(record) ||
                record.iceRestartIntentId != null
        if (!hasNegotiationLifecycle) {
            return false
        }

        onLog(
            "NEGOTIATION_INTENT_CLOSE_REQUEST session=$sessionId edge=$remoteModuleId " +
                "intentId=${resolvedIntentId ?: "NONE"} terminal=$terminal source=$source reason=$reason"
        )

        val intentState = preTerminalIntentState ?: when (terminal) {
            "BLOCKED_BY_GLARE" -> RecoveryNegotiationObservation.IntentState.BLOCKED_BY_GLARE
            "EXECUTED" -> RecoveryNegotiationObservation.IntentState.EXECUTED
            else -> RecoveryNegotiationObservation.IntentState.EXPIRED
        }
        negotiationObservationContext(sessionId, remoteModuleId)?.let { ctx ->
            RecoveryNegotiationObservation.emitIntentFromContext(
                ctx,
                localModuleId,
                intentState,
                reason
            )
            RecoveryNegotiationObservation.emitIntentTerminalFromContext(ctx, terminal, reason)
        } ?: run {
            RecoveryNegotiationObservation.emitIntentTerminal(
                sessionId = sessionId,
                edgeModuleId = remoteModuleId,
                intentId = resolvedIntentId ?: "NONE",
                terminalState = terminal,
                reason = reason
            )
        }

        record.negotiationIntentTerminalEmitted = true
        record.negotiationIntentTerminalState = terminal

        val releaseKind = when (terminal) {
            "SUPERSEDED" -> DeferredIntentAuthority.ReleaseKind.SUPERSEDE
            "EXECUTED" -> DeferredIntentAuthority.ReleaseKind.SLOT_AFTER_EXECUTED
            else -> DeferredIntentAuthority.ReleaseKind.TERMINAL_DISCARD
        }
        val releaseDomain = when {
            reason.startsWith("OBLIGATION_CLOSE") || source == "OBLIGATION_CLOSE" ->
                DeferredIntentAuthority.RequestingDomain.CONTROL
            terminal == "EXECUTED" || source == "NEGOTIATION_DRAIN" ->
                DeferredIntentAuthority.RequestingDomain.NEGOTIATION
            else -> DeferredIntentAuthority.RequestingDomain.MEDIA
        }
        if (markAuthorityExecuted && resolvedIntentId != null) {
            deferredIntentAuthority.markExecuted(resolvedIntentId)
        }
        if (resolvedIntentId != null && record.iceRestartIntentId == resolvedIntentId) {
            releaseDeferredIntentSlot(
                record = record,
                reason = reason,
                domain = releaseDomain,
                kind = releaseKind,
                expireCause = reason
            )
        }
        if (clearDeferralAfterClose && terminal != "EXECUTED") {
            clearDeferralFields(record)
        }
        return true
    }

    /**
     * INV-DI-001: terminal transition via [closeNegotiationIntent] + [releaseDeferredIntentSlot].
     */
    private fun expireDeferredIceRestartIntent(record: EdgeRecoveryRecord, cause: String) {
        if (!hasDeferredMediaAction(record) && record.iceRestartIntentId == null) return
        val binding = record.wakeupBinding
        val intentId = record.iceRestartIntentId ?: "NONE"
        if (binding != null) {
            onLog(
                "RECOVERY_WAKEUP_EXPIRED session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} intentId=$intentId trigger=${binding.sourceType} " +
                    "wakeupBinding=${binding.logLabel()} cause=$cause"
            )
        }
        if (!isDeferredIceRestartIntent(record) && record.iceRestartIntentId == null) return
        val terminal = resolveNegotiationIntentTerminal(cause)
        closeNegotiationIntent(
            record = record,
            intentId = record.iceRestartIntentId,
            terminal = terminal,
            reason = if (terminal == "SUPERSEDED") "SUPERSEDED" else cause,
            source = resolveNegotiationIntentCloseSource(cause)
        )
    }

    private fun isRecoveryDispatchReady(sessionId: String, remoteModuleId: String): Boolean {
        if (!canDispatchRecoveryMediaAction(sessionId, remoteModuleId)) return false
        val admission = evaluateRecoveryAdmission(sessionId, remoteModuleId)
        return admission.decision == AdmissionDecisionProjection.DISPATCH_NOW
    }

    private fun enterHeldNegotiation(
        sessionId: String,
        remoteModuleId: String,
        record: EdgeRecoveryRecord,
        attemptId: Long,
        obligationGen: Long,
        intentId: String,
        probe: IceRestartGateProbe
    ) {
        record.deferredIntentHoldReason = DeferredIntentHoldReason.NEGOTIATION
        record.deferredGateBlockReason = probe.blockReason
        record.wakeupBinding = WakeupBinding(
            sourceType = WakeupSourceType.NEGOTIATION_CAN_EXECUTE,
            sourceKey = edgeWakeupKey(sessionId, remoteModuleId)
        )
        onLog(
            "RECOVERY_ICE_RESTART_DRAIN_HELD session=$sessionId remote=$remoteModuleId " +
                "attempt=$attemptId intentId=$intentId obligationGen=$obligationGen " +
                "reason=gate_not_executable " +
                "gateBlock=${probe.blockReason ?: "UNKNOWN"} " +
                "signalingState=${probe.signalingState ?: "UNKNOWN"}"
        )
        onLog(
            "DEFERRED_INTENT_HELD session=$sessionId remote=$remoteModuleId " +
                "intentId=$intentId attemptId=$attemptId obligationGen=$obligationGen " +
                "hold=NEGOTIATION reason=gate_not_executable " +
                "gateBlock=${probe.blockReason ?: "UNKNOWN"} " +
                "signalingState=${probe.signalingState ?: "UNKNOWN"}"
        )
        observeNegotiationOwner(record, "GATE_NOT_EXECUTABLE")
        negotiationObservationContext(sessionId, remoteModuleId)?.let { ctx ->
            RecoveryNegotiationObservation.emitIntentFromContext(
                ctx,
                localModuleId,
                RecoveryNegotiationObservation.IntentState.BLOCKED,
                probe.blockReason?.name ?: "GATE_NOT_EXECUTABLE"
            )
        }
    }

    private fun enterHeldDispatch(
        sessionId: String,
        remoteModuleId: String,
        record: EdgeRecoveryRecord,
        attemptId: Long,
        obligationGen: Long,
        intentId: String,
        probe: IceRestartGateProbe
    ) {
        record.deferredIntentHoldReason = DeferredIntentHoldReason.DISPATCH
        record.deferredIntentDrainRetryCount++
        record.wakeupBinding = WakeupBinding(
            sourceType = WakeupSourceType.ROUTE_CONVERGED,
            sourceKey = edgeWakeupKey(sessionId, remoteModuleId)
        )
        deferredIntentAuthority.markHeldDispatch(intentId)
        onLog(
            "DEFERRED_INTENT_HELD session=$sessionId remote=$remoteModuleId " +
                "intentId=$intentId attemptId=$attemptId obligationGen=$obligationGen " +
                "hold=DISPATCH reason=dispatch_not_ready " +
                "retryCount=${record.deferredIntentDrainRetryCount} " +
                "negotiationExecutable=true " +
                "gateBlock=${probe.blockReason ?: "NONE"} " +
                "signalingState=${probe.signalingState ?: "UNKNOWN"}"
        )
    }

    /**
     * PR5-2c-C: dispatch-readiness seam retry for HELD(dispatch_not_ready).
     * MUST NOT synthesize NEGOTIATION_CAN_EXECUTE.
     */
    fun retryHeldDeferredIntentDrain(
        sessionId: String,
        remoteModuleId: String,
        seamTrigger: String
    ) {
        drainPendingIceRestartInternal(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            capabilityEventObservationSeq = null,
            trigger = DeferredIntentDrainTrigger.DISPATCH_READINESS_RETRY,
            seamLabel = seamTrigger
        )
    }

    /**
     * INV-NEG-005 / INV-REC-025 / INV-NEG-019: Coordinator routes NEGOTIATION_CAN_EXECUTE here
     * after capability rising-edge. Re-validates post-baseline freshness, re-probe, and
     * current-slot lineage (intentId + attemptId + obligationGen) before dispatch.
     *
     * PR5-2c-C: additionally requires recovery dispatch readiness before EXECUTED.
     *
     * @param capabilityEventObservationSeq observation seq of the rising-edge event; when
     *   non-null MUST be strictly greater than the intent's DEFER_ADMISSION baseline seq.
     *   Null is a test seam / dispatch-readiness retry (not a negotiation capability consume).
     */
    fun drainPendingIceRestart(
        sessionId: String,
        remoteModuleId: String,
        capabilityEventObservationSeq: Long? = null
    ) {
        drainPendingIceRestartInternal(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            capabilityEventObservationSeq = capabilityEventObservationSeq,
            trigger = DeferredIntentDrainTrigger.NEGOTIATION_CAN_EXECUTE,
            seamLabel = null
        )
    }

    private fun drainPendingIceRestartInternal(
        sessionId: String,
        remoteModuleId: String,
        capabilityEventObservationSeq: Long?,
        trigger: DeferredIntentDrainTrigger,
        seamLabel: String?
    ) {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: return
        if (!isDeferredIceRestartIntent(record)) {
            if (seamLabel == Pr52cDebugInjection.DEBUG_RELEASE_SEAM) {
                onLog(
                    "DEFERRED_INTENT_DRAIN_RETRY_SKIPPED session=$sessionId remote=$remoteModuleId " +
                        "intentId=${record.iceRestartIntentId ?: "NONE"} " +
                        "seam=$seamLabel reason=not_negotiation_deferred_slot " +
                        "deferredReason=${record.deferredReason ?: "NONE"} " +
                        "hold=${record.deferredIntentHoldReason ?: "NONE"}"
                )
            }
            return
        }
        val attemptId = record.recoveryAttemptId
        val obligationGen = record.obligationGeneration
        val capturedIntentId = record.iceRestartIntentId
        val intentId = capturedIntentId ?: "NONE"
        // J-X-6: SUPERSEDED intents may observe late drain attempts but must not execute.
        if (capturedIntentId != null &&
            deferredIntentAuthority.observeLateEvent(
                capturedIntentId,
                "DEFERRED_INTENT_DRAIN_ATTEMPT:${trigger.name}"
            ) == DeferredIntentAuthority.LateEventDisposition.AUDIT_ONLY
        ) {
            return
        }
        val admissionSeq = record.deferAdmissionObservationSeq
        if (trigger == DeferredIntentDrainTrigger.DISPATCH_READINESS_RETRY) {
            onLog(
                "DEFERRED_INTENT_DRAIN_RETRY session=$sessionId remote=$remoteModuleId " +
                    "intentId=$intentId attemptId=$attemptId obligationGen=$obligationGen " +
                    "admissionSeq=${admissionSeq ?: "NONE"} seam=${seamLabel ?: "UNKNOWN"} " +
                    "retryCount=${record.deferredIntentDrainRetryCount}"
            )
            if (record.deferredIntentHoldReason != DeferredIntentHoldReason.DISPATCH) return
        }
        onLog(
            "DEFERRED_INTENT_DRAIN_ATTEMPT session=$sessionId remote=$remoteModuleId " +
                "intentId=$intentId attemptId=$attemptId obligationGen=$obligationGen " +
                "admissionSeq=${admissionSeq ?: "NONE"} " +
                "eventSeq=${capabilityEventObservationSeq ?: "NONE"} " +
                "trigger=${trigger.name}"
        )
        if (!record.edgeObligationOpen()) {
            onLog(
                "DEFERRED_INTENT_REJECTED session=$sessionId remote=$remoteModuleId " +
                    "intentId=$intentId attemptId=$attemptId obligationGen=$obligationGen " +
                    "reason=obligation_closed"
            )
            expireDeferredIceRestartIntent(record, "DRAIN_OBLIGATION_CLOSED")
            return
        }
        if (record.iceRestartIssued) {
            onLog(
                "DEFERRED_INTENT_REJECTED session=$sessionId remote=$remoteModuleId " +
                    "intentId=$intentId attemptId=$attemptId obligationGen=$obligationGen " +
                    "reason=already_issued"
            )
            expireDeferredIceRestartIntent(record, "DRAIN_ALREADY_ISSUED")
            return
        }
        if (admissionSeq == null) {
            onLog(
                "DEFERRED_INTENT_REJECTED session=$sessionId remote=$remoteModuleId " +
                    "intentId=$intentId attemptId=$attemptId obligationGen=$obligationGen " +
                    "reason=baseline_missing"
            )
            return
        }
        if (trigger == DeferredIntentDrainTrigger.NEGOTIATION_CAN_EXECUTE &&
            capabilityEventObservationSeq != null &&
            capabilityEventObservationSeq <= admissionSeq
        ) {
            onLog(
                "DEFERRED_INTENT_REJECTED session=$sessionId remote=$remoteModuleId " +
                    "intentId=$intentId attemptId=$attemptId obligationGen=$obligationGen " +
                    "reason=stale_capability_event " +
                    "admissionSeq=$admissionSeq eventSeq=$capabilityEventObservationSeq"
            )
            return
        }
        val probe = probeIceRestartGate(sessionId, remoteModuleId)
        val dispatchReady = isRecoveryDispatchReady(sessionId, remoteModuleId)
        onLog(
            "DEFERRED_INTENT_REPROBE_RESULT session=$sessionId remote=$remoteModuleId " +
                "intentId=$intentId attemptId=$attemptId obligationGen=$obligationGen " +
                "trigger=${trigger.name} " +
                "negotiationExecutable=${probe.executable} " +
                "dispatchReady=$dispatchReady " +
                "gateBlock=${probe.blockReason ?: "NONE"} " +
                "signalingState=${probe.signalingState ?: "UNKNOWN"}"
        )
        if (!probe.executable) {
            enterHeldNegotiation(
                sessionId = sessionId,
                remoteModuleId = remoteModuleId,
                record = record,
                attemptId = attemptId,
                obligationGen = obligationGen,
                intentId = intentId,
                probe = probe
            )
            return
        }
        if (!dispatchReady) {
            enterHeldDispatch(
                sessionId = sessionId,
                remoteModuleId = remoteModuleId,
                record = record,
                attemptId = attemptId,
                obligationGen = obligationGen,
                intentId = intentId,
                probe = probe
            )
            return
        }
        if (trigger == DeferredIntentDrainTrigger.NEGOTIATION_CAN_EXECUTE) {
            onLog(
                "RECOVERY_WAKEUP_FIRED session=$sessionId edge=$remoteModuleId " +
                    "attempt=$attemptId intentId=$intentId trigger=NEGOTIATION_CAN_EXECUTE " +
                    "wakeupBinding=${record.wakeupBinding?.logLabel()}"
            )
        }
        val still = edges[key] ?: return
        if (still.recoveryAttemptId != attemptId ||
            still.obligationGeneration != obligationGen ||
            still.iceRestartIntentId != capturedIntentId ||
            !still.edgeObligationOpen() ||
            still.iceRestartIssued
        ) {
            onLog(
                "DEFERRED_INTENT_REJECTED session=$sessionId remote=$remoteModuleId " +
                    "intentId=$intentId attemptId=$attemptId obligationGen=$obligationGen " +
                    "reason=STALE_DEFERRED_INTENT lineage_mismatch " +
                    "currentIntent=${still.iceRestartIntentId ?: "NONE"} " +
                    "currentAttempt=${still.recoveryAttemptId} " +
                    "currentGen=${still.obligationGeneration}"
            )
            expireDeferredIceRestartIntent(still, "DRAIN_STALE_LINEAGE")
            return
        }
        val drainTerminalReason = when (trigger) {
            DeferredIntentDrainTrigger.NEGOTIATION_CAN_EXECUTE ->
                "DRAIN_AFTER_NEGOTIATION_CAN_EXECUTE"
            DeferredIntentDrainTrigger.DISPATCH_READINESS_RETRY ->
                "DRAIN_AFTER_DISPATCH_READINESS_RETRY"
        }
        onLog(
            "RECOVERY_ICE_RESTART_INTENT_TERMINAL session=$sessionId remote=$remoteModuleId " +
                "attempt=$attemptId intentId=$intentId obligationGen=$obligationGen " +
                "terminal=EXECUTED reason=$drainTerminalReason " +
                "gateBlock=${still.deferredGateBlockReason ?: "NONE"}"
        )
        clearDeferralFields(still)
        // Keep intentId through DISPATCH audit, then drop via Authority after EXECUTED.
        still.iceRestartIntentId = capturedIntentId
        issueBoundedIceRestart(still, RecoveryReason.NETWORK_RECOVERY)
        val dispatchAt = still.restartDispatchAtMs
        onLog(
            "DEFERRED_INTENT_EXECUTED session=$sessionId remote=$remoteModuleId " +
                "intentId=$intentId attemptId=$attemptId obligationGen=$obligationGen " +
                "restartAttemptId=$attemptId dispatchAt=${dispatchAt ?: "NONE"}"
        )
        closeNegotiationIntent(
            record = still,
            intentId = capturedIntentId,
            terminal = "EXECUTED",
            reason = drainTerminalReason,
            source = "NEGOTIATION_DRAIN",
            preTerminalIntentState = RecoveryNegotiationObservation.IntentState.EXECUTED,
            markAuthorityExecuted = true,
            clearDeferralAfterClose = false
        )
    }

    private fun clearValidationFenceIfArmed(
        sessionId: String,
        remoteModuleId: String,
        intentId: String?,
        reason: String
    ) {
        if (intentId == null) return
        if (Pr52cDebugInjection.fencedIntentId(sessionId, remoteModuleId) != intentId) return
        Pr52cDebugInjection.clearValidationFence(sessionId, remoteModuleId)
        onLog(
            "DEFERRED_INTENT_VALIDATION_FENCE_CLEARED session=$sessionId remote=$remoteModuleId " +
                "intentId=$intentId reason=$reason"
        )
    }

    /**
     * ADR-0022 R28-K / INV-REC-001: attempt failure timers MUST NOT run while required
     * recovery capability is unavailable (route, media dispatch gate, deferred action).
     */
    private fun isCapabilityBlockingAttemptClock(record: EdgeRecoveryRecord): Boolean {
        if (hasDeferredMediaAction(record)) {
            when (record.deferredReason) {
                DeferredReason.ROUTE_NOT_READY,
                DeferredReason.MEDIA_NOT_READY,
                DeferredReason.AUTHORITY_NOT_READY,
                DeferredReason.NEGOTIATION_SETTLING -> return true
                null -> Unit
            }
        }
        if (
            !record.initiatesReattach &&
            !record.iceRestartIssued &&
            (record.mediaActionOwner == MediaActionOwner.PENDING || hasDeferredMediaAction(record)) &&
            !canDispatchRecoveryMediaAction(record.key.sessionId, record.key.remoteModuleId)
        ) {
            return true
        }
        return false
    }

    /**
     * Appendix C-2: recovery authority claims media action when no participant handoff owns it.
     * Invoked after EDGE_STARTED and on material re-evaluate when still PENDING or DEFERRED.
     */
    private fun resolveMediaActionOwner(
        record: EdgeRecoveryRecord,
        recoveryReason: RecoveryReason,
        immediate: Boolean,
        trigger: String,
        mediaReady: Boolean? = null
    ) {
        if (record.initiatesReattach) return
        if (record.mediaActionOwner.isAssigned() && !hasDeferredMediaAction(record)) return
        if (!record.phase.isActivelyRecovering()) return
        val key = record.key
        val fencedIntentId = record.iceRestartIntentId
        if (
            fencedIntentId != null &&
            Pr52cDebugInjection.shouldSuppressProductionDeferredDrain(
                key.sessionId,
                key.remoteModuleId,
                fencedIntentId,
                trigger
            )
        ) {
            onLog(
                "DEFERRED_INTENT_VALIDATION_FENCE session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "intentId=$fencedIntentId seam=$trigger action=suppress_production_media_resolve"
            )
            return
        }
        if (hasParticipantHandoffPending(record)) {
            recordMediaActionDeferred(
                record = record,
                owner = MediaActionOwner.PARTICIPANT_REATTACH,
                reason = DeferredReason.MEDIA_NOT_READY,
                wakeupBinding = WakeupBinding(
                    sourceType = WakeupSourceType.ROUTE_CONVERGED,
                    sourceKey = edgeWakeupKey(key.sessionId, key.remoteModuleId)
                ),
                trigger = "PARTICIPANT_HANDOFF_PENDING:$trigger"
            )
            return
        }
        val dispatchReady = mediaReady ?: canDispatchRecoveryMediaAction(key.sessionId, key.remoteModuleId)
        if (!immediate && !dispatchReady) {
            recordMediaActionDeferred(
                record = record,
                owner = MediaActionOwner.HOST_RESTART,
                reason = DeferredReason.MEDIA_NOT_READY,
                wakeupBinding = WakeupBinding(
                    sourceType = WakeupSourceType.ROUTE_CONVERGED,
                    sourceKey = edgeWakeupKey(key.sessionId, key.remoteModuleId)
                ),
                trigger = trigger
            )
            return
        }
        onLog(
            "RECOVERY_MEDIA_ACTION_ASSIGNMENT session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} owner=HOST_RESTART trigger=$trigger"
        )
        observeNegotiationOwner(record, trigger)
        issueBoundedIceRestart(record, recoveryReason)
    }

    private fun logObligationCloseRequested(
        record: EdgeRecoveryRecord,
        reason: ObligationCloseReason,
        closeEvidence: String?
    ) {
        val key = record.key
        val iceConnected = isIceConnected(key.sessionId, key.remoteModuleId)
        onLog(
            "RECOVERY_OBLIGATION_CLOSE_REQUESTED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} reason=$reason phase=${record.phase} " +
                "evidence=${closeEvidence ?: "NONE"} mediaRestored=${record.mediaRestored} " +
                "mediaReady=${record.mediaRestored || iceConnected} iceConnected=$iceConnected " +
                "controlPlaneStarted=${record.controlPlaneStarted()}"
        )
    }

    private fun logCompletionEvidenceAccepted(
        record: EdgeRecoveryRecord,
        evidence: String,
        snapshot: EdgeReachabilitySnapshot? = null
    ) {
        val key = record.key
        val iceConnected = isIceConnected(key.sessionId, key.remoteModuleId)
        val mediaReady = record.mediaRestored || iceConnected
        val snapshotFields = snapshot?.let {
            " mediaRouteConnected=${it.mediaRouteConnected} authorityReachable=${it.authorityReachable}"
        } ?: ""
        onLog(
            "RECOVERY_COMPLETION_EVIDENCE_ACCEPTED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} evidence=$evidence mediaReady=$mediaReady " +
                "mediaRestored=${record.mediaRestored} iceConnected=$iceConnected " +
                "controlPlaneStarted=${record.controlPlaneStarted()} phase=${record.phase}" +
                snapshotFields
        )
    }

    private fun completionEvidenceFromReachability(
        record: EdgeRecoveryRecord,
        snapshot: EdgeReachabilitySnapshot,
        trigger: RecoveryReevaluateTrigger
    ): String = when {
        isIceConnected(record.key.sessionId, record.key.remoteModuleId) -> "ICE_CONNECTED"
        record.mediaRestored -> "MEDIA_RESTORED"
        snapshot.mediaRouteConnected && snapshot.authorityReachable -> "ROUTE_CONVERGED"
        else -> trigger.name
    }

    private fun noteMediaRestored(record: EdgeRecoveryRecord) {
        record.mediaRestored = true
        val now = clock()
        val dispatchAt = record.restartDispatchAtMs
        // Same-tick post-dispatch probe must still count as restart-resolved (INV-NEG-016).
        record.mediaRestoredObservedAtMs =
            if (dispatchAt != null && now <= dispatchAt) dispatchAt + 1L else now
    }

    private fun clearMediaRestoredFact(record: EdgeRecoveryRecord) {
        record.mediaRestored = false
        record.mediaRestoredObservedAtMs = null
    }

    /**
     * Enter failed-media residency: attempt terminal, obligation stays OPEN, stamp deadline.
     * When [explicitAbort] is true, emit EXPLICIT_RECOVERY_ABORT instead of FAILED_MEDIA_RECOVERY
     * (ADR-0022 Appendix C-1).
     */
    private fun enterFailedMediaResidency(
        record: EdgeRecoveryRecord,
        reason: String,
        explicitAbort: Boolean = false,
        failureClass: RecoveryFailureClass? = null
    ) {
        if (record.iceRestartIntentId != null || hasDeferredMediaAction(record)) {
            closeNegotiationIntent(
                record = record,
                intentId = record.iceRestartIntentId,
                terminal = "EXPIRED",
                reason = reason,
                source = "ATTEMPT_FAILURE"
            )
        }
        val resolvedClass = failureClass ?: when {
            explicitAbort -> RecoveryFailureClass.EXPLICIT_ABORT
            reason == "attempt_timeout" -> RecoveryFailureClass.UNKNOWN_RECOVERY_TIMEOUT
            else -> RecoveryFailureClass.MEDIA_PATH_FAILED
        }
        val oldPhase = record.phase
        record.phase = EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY
        logPhaseTransition(record, oldPhase, record.phase, if (explicitAbort) "EXPLICIT_ABORT:$reason" else "FAILED_MEDIA:$reason")
        val terminalAt = clock()
        record.obligationDeadlineAtMs = terminalAt + observationWindowMs
        if (explicitAbort) {
            onLog(
                "EXPLICIT_RECOVERY_ABORT session=${record.key.sessionId} " +
                    "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                    "reason=$reason failureClass=$resolvedClass deadlineAt=${record.obligationDeadlineAtMs}"
            )
            notifyAttemptLineageObservation(record, "explicit_recovery_abort")
        } else {
            onLog(
                "FAILED_MEDIA_RECOVERY session=${record.key.sessionId} remote=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} reason=$reason failureClass=$resolvedClass " +
                    "deadlineAt=${record.obligationDeadlineAtMs}"
            )
            notifyAttemptLineageObservation(record, "failed_media_recovery")
        }
        scheduleObligationDeadline(record)
    }

    /** ADR-0036 RCA-4 / RCA-6: classify watchdog timeout before entering failed residency. */
    private fun classifyRecoveryFailureAtTimeout(record: EdgeRecoveryRecord): RecoveryFailureClass {
        val fact = record.controlReconciliationFact?.takeIf { it.isCurrentFor(record) }
        val mediaSatisfied = record.mediaRestored || isIceConnected(record.key.sessionId, record.key.remoteModuleId)
        if (!mediaSatisfied) return RecoveryFailureClass.MEDIA_PATH_FAILED
        if (fact == null) return RecoveryFailureClass.UNKNOWN_RECOVERY_TIMEOUT
        if (!fact.membershipEpochConverged &&
            fact.membershipProbeDisposition == MembershipEpochProbeDisposition.CHECKED
        ) {
            return RecoveryFailureClass.MEMBERSHIP_CONVERGENCE_TIMEOUT
        }
        if (!fact.result && fact.membershipEpochConverged) {
            return RecoveryFailureClass.CONTROL_RECONCILIATION_TIMEOUT
        }
        return RecoveryFailureClass.UNKNOWN_RECOVERY_TIMEOUT
    }

    private fun emitRecoveryCompletionBlocked(
        record: EdgeRecoveryRecord,
        blockedReason: String
    ) {
        onLog(
            "RECOVERY_COMPLETION_BLOCKED_BY_CONTROL session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                "obligationGen=${record.obligationGeneration} reason=$blockedReason"
        )
    }

    private fun shouldDeferWatchdogForMembershipConvergence(record: EdgeRecoveryRecord): Boolean {
        val channelId = record.channelId ?: return false
        val fact = record.controlReconciliationFact?.takeIf { it.isCurrentFor(record) } ?: return false
        if (fact.membershipEpochConverged) return false
        if (fact.membershipProbeDisposition != MembershipEpochProbeDisposition.CHECKED) return false
        return isMembershipConvergenceInFlight(channelId, record.obligationGeneration)
    }

    private fun shouldDeferWatchdogForControlReconciliation(record: EdgeRecoveryRecord): Boolean {
        if (shouldDeferWatchdogForMembershipConvergence(record)) return false
        val fact = record.controlReconciliationFact?.takeIf { it.isCurrentFor(record) } ?: return false
        if (fact.result) return false
        val mediaSatisfied = record.mediaRestored ||
            isIceConnected(record.key.sessionId, record.key.remoteModuleId)
        if (!mediaSatisfied || !record.controlPlaneStarted()) return false
        if (!fact.membershipEpochConverged &&
            fact.membershipProbeDisposition == MembershipEpochProbeDisposition.CHECKED
        ) {
            return false
        }
        if (!fact.controlHandshakeCompleted || !fact.sessionEpochMatched) return true
        if (fact.membershipEpochConverged) return true
        return false
    }

    private fun enterFailedRequiresUserAction(record: EdgeRecoveryRecord) {
        record.phase = EdgeRecoveryPhase.FAILED_REQUIRES_USER_ACTION
        val terminalAt = clock()
        record.obligationDeadlineAtMs = terminalAt + observationWindowMs
        scheduleObligationDeadline(record)
    }

    private fun scheduleObligationDeadline(record: EdgeRecoveryRecord) {
        val key = record.key
        cancelDeadline(key)
        val deadlineAt = record.obligationDeadlineAtMs ?: return
        val delayMs = (deadlineAt - clock()).coerceAtLeast(0L)
        val future = scheduler.schedule({
            val current = edges[key] ?: return@schedule
            if (current.obligationClosedAtMs != null) return@schedule
            // Wall-clock delay already encodes observationWindow; do not re-gate on [clock]
            // so injected test clocks that do not advance still close on schedule.
            if (current.obligationDeadlineAtMs == null) return@schedule
            // Only close from failed-media residency. Active Attempt N+1 after SUPERSEDE
            // must not be killed by a stale timer from the prior failed entry.
            if (!current.phase.isFailedMediaRecovery()) return@schedule
            RecoveryCompletionPolicy.closeObligation(
                completionMutationHost,
                current,
                ObligationCloseReason.OBLIGATION_DEADLINE,
                "OBLIGATION_DEADLINE"
            )
            notifyChanged(key.sessionId)
        }, delayMs, TimeUnit.MILLISECONDS)
        deadlineTimers[key] = future
    }

    private fun cancelDeadline(key: ConferenceEdgeKey) {
        deadlineTimers.remove(key)?.cancel(false)
    }

    /**
     * Capability materiality notification from Coordinator (ADR-0022 R28-G).
     * Fact writers MUST NOT call this 鈥?only [TalkbackCoordinator] after signature comparison.
     *
     * 搂13.2.4 Gap-2: when obligation is CLOSED, fresh [RecoveryResurrectionEvidence] may admit a
     * Successor Obligation Episode. OPEN path MUST NOT bump [obligationGeneration].
     */
    fun onRecoveryReachabilityChanged(
        sessionId: String,
        channelId: String,
        remoteModuleId: String,
        snapshot: EdgeReachabilitySnapshot,
        signature: RecoveryCapabilitySignature,
        capabilityBefore: RecoveryCapabilitySignature?,
        trigger: RecoveryReevaluateTrigger,
        resurrectionEvidence: RecoveryResurrectionEvidence? = null
    ) {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: return
        if (resurrectionEvidence != null &&
            (trigger != RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED ||
                resurrectionEvidence.kind != RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED)
        ) {
            onLog(
                "RECOVERY_INVALID_EVIDENCE_BINDING session=$sessionId edge=$remoteModuleId " +
                    "trigger=$trigger evidenceKind=${resurrectionEvidence.kind} " +
                    "attempt=${record.recoveryAttemptId} obligationGen=${record.obligationGeneration}"
            )
            return
        }
        if (record.edgeObligationOpen()) {
            reevaluateOpenObligation(
                record = record,
                snapshot = snapshot,
                signature = signature,
                capabilityBefore = capabilityBefore,
                trigger = trigger
            )
            return
        }
        if (isFreshRemoteModuleRecoveredEvidence(record, resurrectionEvidence)) {
            // Harness primitive only — does not change production successor ownership (R4).
            if (
                SuppressSuccessorAttemptDebugInjection.trySuppressAdmission(
                    sessionId = key.sessionId,
                    remoteModuleId = key.remoteModuleId,
                    originalAttemptId = record.recoveryAttemptId,
                    generation = record.obligationGeneration,
                    nowMs = clock(),
                    log = onLog
                )
            ) {
                onLog(
                    "RECOVERY_REACHABILITY_IGNORED session=$sessionId edge=$remoteModuleId " +
                        "trigger=$trigger reason=suppress_successor_attempt " +
                        "attempt=${record.recoveryAttemptId} obligationGen=${record.obligationGeneration}"
                )
                return
            }
            admitSuccessorObligationEpisode(
                record = record,
                channelId = channelId,
                signature = signature,
                evidence = resurrectionEvidence!!
            )
            return
        }
        onLog(
            "RECOVERY_REACHABILITY_IGNORED session=$sessionId edge=$remoteModuleId " +
                "trigger=$trigger reason=no_open_obligation_or_fresh_resurrection " +
                "attempt=${record.recoveryAttemptId} obligationGen=${record.obligationGeneration}"
        )
    }

    /**
     * INV-REC-022: lineage / completion terminal authority is attempt-scoped within the current
     * obligation generation. Historical attempts / closed gens MUST NOT terminate successor state.
     * An already-closed obligation (e.g. [ObligationCloseReason.OBLIGATION_DEADLINE]) also rejects
     * late RECOVERED facts so they cannot rewrite [EdgeRecoveryPhase] and poison successor admission.
     */
    fun canMarkLineageRecovered(
        sessionId: String,
        remoteModuleId: String,
        factAttemptId: Long,
        factObligationGeneration: Long
    ): Boolean {
        val current = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        if (current.obligationClosedAtMs != null) return false
        return factAttemptId == current.recoveryAttemptId &&
            factObligationGeneration == current.obligationGeneration
    }

    /**
     * Test seam: apply [markRecovered] against the live edge record (same object a racing
     * completion callback would hold after exclusive close).
     */
    internal fun applyMarkRecoveredForTest(
        sessionId: String,
        remoteModuleId: String,
        evidence: String = "ICE_CONNECTED"
    ) {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return
        RecoveryCompletionPolicy.markRecovered(completionMutationHost, record, evidence)
    }

    /** Test seam: seed edge + refresh membership probe / control reconciliation (E.18). */
    internal fun refreshControlReconciliationForTest(record: EdgeRecoveryRecord) {
        edges[record.key] = record
        refreshControlReconciliationFact(record)
    }

    /**
     * ADR-0036 Phase 2.4 Fix-D: authority digest observation changed.
     *
     * Bypasses Coordinator capability materiality gate so completion authority re-consumes
     * the new membership fact. Does **not** assume converged — only re-runs the existing
     * membership / control reconciliation predicates and completion evaluation.
     */
    fun forceRefreshControlReconciliationAfterDigestRefresh(
        sessionId: String,
        channelId: String,
        reason: String = "DIGEST_REFRESH",
        oldDigestEpoch: Long? = null,
        oldDigestHash: Int? = null,
        newDigestEpoch: Long? = null,
        newDigestHash: Int? = null
    ) {
        val digestAudit = RecoveryControlReconciliationFact.DigestRefreshAudit(
            oldDigestEpoch = oldDigestEpoch,
            oldDigestHash = oldDigestHash,
            newDigestEpoch = newDigestEpoch,
            newDigestHash = newDigestHash
        )
        val targets = edges.values.filter {
            it.key.sessionId == sessionId &&
                it.channelId == channelId &&
                it.edgeObligationOpen()
        }
        if (targets.isEmpty()) {
            onLog(
                "CONTROL_RECONCILIATION_REFRESH_TRIGGERED reason=$reason " +
                    "session=$sessionId channel=$channelId edges=0 " +
                    "oldDigestEpoch=${oldDigestEpoch ?: "null"} " +
                    "oldDigestHash=${oldDigestHash ?: "null"} " +
                    "newDigestEpoch=${newDigestEpoch ?: "null"} " +
                    "newDigestHash=${newDigestHash ?: "null"} " +
                    "skipped=NO_OPEN_OBLIGATION"
            )
            return
        }
        targets.forEach { record ->
            onLog(
                "CONTROL_RECONCILIATION_REFRESH_TRIGGERED reason=$reason " +
                    "session=$sessionId channel=$channelId remote=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} obligationGen=${record.obligationGeneration} " +
                    "oldDigestEpoch=${oldDigestEpoch ?: "null"} " +
                    "oldDigestHash=${oldDigestHash ?: "null"} " +
                    "newDigestEpoch=${newDigestEpoch ?: "null"} " +
                    "newDigestHash=${newDigestHash ?: "null"}"
            )
            refreshControlReconciliationFact(record, digestAudit)
            val snapshot = buildCompletionEvaluationSnapshot(record)
            val signature = projectRecoveryCapabilitySignature(
                snapshot = snapshot,
                initiatesReattach = record.initiatesReattach,
                controlPlaneStarted = record.controlPlaneStarted()
            )
            runCompletionEvaluationStub(
                record = record,
                snapshot = snapshot,
                signature = signature,
                trigger = RecoveryReevaluateTrigger.DIGEST_REFRESH,
                skipMembershipRefresh = true
            )
            emitCompletionObservations(record, snapshot, RecoveryReevaluateTrigger.DIGEST_REFRESH)
        }
        notifyChanged(sessionId)
    }

    private fun reevaluateOpenObligation(
        record: EdgeRecoveryRecord,
        snapshot: EdgeReachabilitySnapshot,
        signature: RecoveryCapabilitySignature,
        capabilityBefore: RecoveryCapabilitySignature?,
        trigger: RecoveryReevaluateTrigger
    ) {
        val sessionId = record.key.sessionId
        val remoteModuleId = record.key.remoteModuleId
        if (hasDeferredWakeupForTrigger(sessionId, remoteModuleId, trigger)) {
            onLog(
                "RECOVERY_WAKEUP_FIRED session=$sessionId edge=$remoteModuleId " +
                    "attempt=${record.recoveryAttemptId} trigger=$trigger " +
                    "wakeupBinding=${record.wakeupBinding?.logLabel()}"
            )
            val fencedIntentId = record.iceRestartIntentId
            val productionDrainSuppressed = fencedIntentId != null &&
                Pr52cDebugInjection.shouldSuppressProductionDeferredDrain(
                    sessionId,
                    remoteModuleId,
                    fencedIntentId,
                    trigger.name
                )
            if (productionDrainSuppressed) {
                onLog(
                    "DEFERRED_INTENT_VALIDATION_FENCE session=$sessionId remote=$remoteModuleId " +
                        "intentId=$fencedIntentId seam=${trigger.name} action=suppress_production_drain"
                )
            } else if (
                record.deferredIntentHoldReason == DeferredIntentHoldReason.DISPATCH &&
                isNegotiationDeferredIceRestartSlot(record)
            ) {
                retryHeldDeferredIntentDrain(sessionId, remoteModuleId, trigger.name)
            } else if (
                hasDeferredMediaAction(record) &&
                record.mediaActionOwner == MediaActionOwner.HOST_RESTART
            ) {
                resolveMediaActionOwner(
                    record = record,
                    recoveryReason = RecoveryReason.NETWORK_RECOVERY,
                    immediate = false,
                    trigger = trigger.name
                )
            }
        }
        if (
            trigger == RecoveryReevaluateTrigger.LINK_READY ||
            trigger == RecoveryReevaluateTrigger.PEER_REACHABILITY_RESTORED
        ) {
            recoveryOfferDeliveryPolicy.onDeliveryHint(record, trigger.name)
        }
        val controlPlane = record.controlPlaneStarted()
        onLog(
            "RECOVERY_REEVALUATE session=$sessionId edge=$remoteModuleId " +
                "attempt=${record.recoveryAttemptId} trigger=$trigger " +
                "capabilityBefore=${capabilityBefore?.formatCapabilityLabel() ?: "NONE"} " +
                "capabilityAfter=${signature.formatCapabilityLabel()} " +
                "controlPlaneStarted=$controlPlane"
        )
        refreshControlReconciliationFact(record)
        runCompletionEvaluationStub(record, snapshot, signature, trigger)
        emitCompletionObservations(record, snapshot, trigger)
        if (trigger == RecoveryReevaluateTrigger.DELIVERY_CONFIRMED) {
            val waiting = signature.waitingReason?.name ?: "NONE"
            val finalDecision = when {
                record.phase == EdgeRecoveryPhase.RECOVERED -> "RECOVERED"
                waiting != "NONE" -> "WAITING($waiting)"
                else -> record.phase.name
            }
            onLog(
                "RECOVERY_PROJECTION_RESULT session=$sessionId edge=$remoteModuleId " +
                    "deliveryConfirmedOutcome=${record.deliveryConfirmedOutcome?.name ?: "NONE"} " +
                    "mediaState=${snapshot.mediaRouteConnected} " +
                    "controlPlaneStarted=${record.controlPlaneStarted()} " +
                    "peerSignaling=${snapshot.peerSignalingReachable} " +
                    "authority=${snapshot.authorityReachable} " +
                    "finalDecision=$finalDecision"
            )
        }
        notifyChanged(sessionId)
    }

    /** PR5-2b: compute + emit Q6-2 control reconciliation fact; store on record for CompletionPolicy. */
    private fun refreshControlReconciliationFact(
        record: EdgeRecoveryRecord,
        digestAudit: RecoveryControlReconciliationFact.DigestRefreshAudit? = null
    ) {
        val channelId = record.channelId
        val conferenceSessionId = record.key.sessionId
        val probeResult = when {
            channelId == null ->
                MembershipEpochProbeResult.Unwired("CHANNEL_CONTEXT_MISSING")
            else ->
                membershipEpochProbe.probe(record, channelId, conferenceSessionId)
        }
        emitMembershipProbeObservation(record, channelId, conferenceSessionId, probeResult)
        val fact = ControlReconciliationEvaluator.evaluate(
            record = record,
            membershipProbe = probeResult,
            clock = clock
        )
        record.controlReconciliationFact = fact
        RecoveryControlReconciliationFact.emit(record, fact, onLog, digestAudit)
    }

    private fun emitMembershipProbeObservation(
        record: EdgeRecoveryRecord,
        channelId: String?,
        conferenceSessionId: String,
        probeResult: MembershipEpochProbeResult
    ) {
        when (probeResult) {
            is MembershipEpochProbeResult.Unwired -> onLog(
                RecoveryControlReconciliationMembershipObservation.formatUnwired(
                    record = record,
                    channelId = channelId,
                    conferenceSessionId = conferenceSessionId,
                    reason = probeResult.reason
                )
            )
            is MembershipEpochProbeResult.Checked -> onLog(
                RecoveryControlReconciliationMembershipObservation.formatChecked(record, probeResult)
            )
        }
    }

    /** PR5-0: read-only completion projection 鈥?no state mutation. */
    private fun emitCompletionObservations(
        record: EdgeRecoveryRecord,
        snapshot: EdgeReachabilitySnapshot,
        trigger: RecoveryReevaluateTrigger
    ) {
        RecoveryAttemptOwner.reconcileFromFacts(record, "OBS:$trigger")
        val key = record.key
        val iceConnected = isIceConnected(key.sessionId, key.remoteModuleId)
        val mediaUnavailableAdvisory =
            isMediaUnavailable(key.sessionId, key.remoteModuleId) ||
                (iceConnected && !record.mediaRestored && !snapshot.mediaRouteConnected)
        val result = CompletionObservationProjection.project(
            record = record,
            snapshot = snapshot,
            iceConnected = iceConnected,
            mediaUnavailableAdvisory = mediaUnavailableAdvisory,
            hasUncoveredDeferredIntent = hasDeferredMediaAction(record)
        )
        val sink = CompletionObservationProjection.testLogSink()
        CompletionObservationProjection.logObservations(
            result = result,
            trigger = trigger,
            logSink = sink ?: onLog
        )
    }

    private fun isFreshRemoteModuleRecoveredEvidence(
        record: EdgeRecoveryRecord,
        evidence: RecoveryResurrectionEvidence?
    ): Boolean {
        if (evidence == null) return false
        if (evidence.kind != RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED) return false
        val closedAt = record.obligationClosedAtMs ?: return false
        if (evidence.observedAtMs <= closedAt) return false
        // Edge Lifecycle ACTIVE == record present (caller resolved).
        // "unhealthy / no media-complete" == not RECOVERED. mediaRestored is attempt-scoped
        // media-plane evidence and MUST NOT block successor admission after OBLIGATION_DEADLINE.
        if (record.phase == EdgeRecoveryPhase.RECOVERED) return false
        return true
    }

    /**
     * ADR-0022 搂13.2.4: admit Successor Obligation Episode (gen+1 + first attempt).
     * B-13.2.4-1: admission 鈮?beginRecovery fusion 鈥?resolve/dispatch separately (M1, INV-REC-023).
     */
    private fun admitSuccessorObligationEpisode(
        record: EdgeRecoveryRecord,
        channelId: String,
        signature: RecoveryCapabilitySignature,
        evidence: RecoveryResurrectionEvidence
    ): SuccessorObligationAdmission {
        val key = record.key
        val initiatesReattach = record.initiatesReattach
        val previousGen = record.obligationGeneration
        val previousAttempt = record.recoveryAttemptId
        // INV-NEG-003: audit predecessor intent before record replacement (G2 does not inherit).
        expireDeferredIceRestartIntent(record, "ADMIT_SUCCESSOR")
        val admitted = openNewRecoveryObligation(
            key = key,
            channelId = channelId.ifBlank { record.channelId },
            phase = EdgeRecoveryPhase.RECOVERY_PENDING,
            initiatesReattach = initiatesReattach,
            trigger = "ADMIT_SUCCESSOR_OBLIGATION_EPISODE"
        )
        onLog(
            "ADMIT_SUCCESSOR_OBLIGATION_EPISODE session=${key.sessionId} remote=${key.remoteModuleId} " +
                "priorGen=$previousGen priorAttempt=$previousAttempt " +
                "obligationGen=${admitted.obligationGeneration} attempt=${admitted.recoveryAttemptId} " +
                "evidenceKind=${evidence.kind} observedAtMs=${evidence.observedAtMs}"
        )
        // E16 ActivationEvidence (SUCCESSOR_START): after Admission, before Delivery prelude.
        emitRecoverySuccessorStarted(
            record = admitted,
            evidence = evidence,
            priorGen = previousGen,
            priorAttempt = previousAttempt
        )
        // M1: same resolve path as R1 / SUPERSEDE; immediate=false; watchdog only after dispatch.
        admitted.mediaActionOwner = MediaActionOwner.PENDING
        clearDeferralFields(admitted)
        val recoveryReason = RecoveryReason.NETWORK_RECOVERY
        if (admitted.initiatesReattach) {
            if (
                RecoveryAction.DISPATCH_REATTACH in signature.permittedActions &&
                !admitted.controlPlaneStarted()
            ) {
                applyReattachDispatchOutcome(
                    record = admitted,
                    outcome = onRequestReattach(key.sessionId, admitted.channelId, key.remoteModuleId),
                    trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED
                )
                if (admitted.phase == EdgeRecoveryPhase.REATTACH_REQUESTED) {
                    scheduleWatchdog(admitted)
                }
            } else {
                recordMediaActionDeferred(
                    record = admitted,
                    owner = MediaActionOwner.PARTICIPANT_REATTACH,
                    reason = DeferredReason.MEDIA_NOT_READY,
                    wakeupBinding = WakeupBinding(
                        sourceType = WakeupSourceType.ROUTE_CONVERGED,
                        sourceKey = edgeWakeupKey(key.sessionId, key.remoteModuleId)
                    ),
                    trigger = "ADMIT_SUCCESSOR:${RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED}"
                )
            }
        } else {
            resolveMediaActionOwner(
                record = admitted,
                recoveryReason = recoveryReason,
                immediate = false,
                trigger = "ADMIT_SUCCESSOR:${RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED}"
            )
            // issueBoundedIceRestart schedules watchdog on dispatch; deferred must not (INV-REC-023).
        }
        notifyChanged(key.sessionId)
        return SuccessorObligationAdmission(
            obligationGeneration = admitted.obligationGeneration,
            recoveryAttemptId = admitted.recoveryAttemptId
        )
    }

    /**
     * E16 Phase-3: ActivationEvidence.kind=SUCCESSOR_START.
     * Once per obligation episode; does not imply transport/ready/delivery success.
     */
    private fun emitRecoverySuccessorStarted(
        record: EdgeRecoveryRecord,
        evidence: RecoveryResurrectionEvidence,
        priorGen: Long,
        priorAttempt: Long
    ) {
        if (record.successorActivationEmitted) return
        record.successorActivationEmitted = true
        val key = record.key
        onLog(
            "RECOVERY_SUCCESSOR_STARTED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "local=$localModuleId obligationGen=${record.obligationGeneration} " +
                "attempt=${record.recoveryAttemptId} pathway=NEW_OBLIGATION_EPISODE " +
                "trigger=ADMIT_SUCCESSOR_OBLIGATION_EPISODE " +
                "priorGen=$priorGen priorAttempt=$priorAttempt " +
                "evidenceKind=${evidence.kind} activationKind=SUCCESSOR_START"
        )
    }

    fun isChannelCancelled(channelId: String): Boolean {
        val expiresAt = cancelledChannels[channelId] ?: return false
        if (clock() > expiresAt) {
            cancelledChannels.remove(channelId)
            return false
        }
        return true
    }

    fun isSessionCancelled(sessionId: String): Boolean {
        val expiresAt = cancelledSessions[sessionId] ?: return false
        if (clock() > expiresAt) {
            cancelledSessions.remove(sessionId)
            return false
        }
        return true
    }

    fun onIceStateChanged(
        sessionId: String,
        channelId: String,
        remoteModuleId: String,
        iceState: String,
        eligibility: EdgeRecoveryEligibility,
        initiatesReattach: Boolean
    ) {
        if (isSessionCancelled(sessionId)) {
            logRecoveryDecision(
                sessionId = sessionId,
                edge = remoteModuleId,
                trigger = RecoveryDecisionTrigger.SESSION_CANCELLED,
                recoveryReason = RecoveryReason.SESSION_CANCELLED,
                terminationReason = RecoveryTerminationReason.CONFERENCE_TERMINATED,
                policy = RecoveryDecisionPolicy.NO_RECOVERY,
                approved = false,
                rejectReason = "session_cancelled",
                attempt = edges[ConferenceEdgeKey(sessionId, remoteModuleId)]?.recoveryAttemptId
            )
            onLog("RECOVERY_EVENT_DROPPED session=$sessionId remote=$remoteModuleId reason=session_cancelled")
            return
        }
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        if (IceConnectivity.isConnected(iceState)) {
            // Always drop debounce suspicion on CONNECTED (R28-H.2); onIceConnected decides HEALTHY vs evaluation.
            cancelDebounce(key)
            onIceConnected(sessionId, remoteModuleId)
            return
        }
        if (iceState != "DISCONNECTED" && iceState != "FAILED") return

        val record = edges[key]
        if (record != null) clearMediaRestoredFact(record)
        if (record?.phase == EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY ||
            record?.phase == EdgeRecoveryPhase.FAILED_REQUIRES_USER_ACTION ||
            record?.phase == EdgeRecoveryPhase.FAILED_IDENTITY_MISMATCH ||
            record?.phase == EdgeRecoveryPhase.FAILED_STALE_LINEAGE
        ) {
            return
        }

        if (iceState == "FAILED") {
            cancelDebounce(key)
            val existing = edges[key]
            if (existing?.hasActiveAttempt() == true &&
                existing.phase != EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING
            ) {
                onLog(
                    "RECOVERY_EVENT_ATTACHED_EXISTING_ATTEMPT session=$sessionId remote=$remoteModuleId " +
                        "attempt=${existing.recoveryAttemptId} trigger=ICE_FAILED"
                )
                return
            }
            beginRecovery(
                key,
                channelId,
                eligibility,
                initiatesReattach,
                immediate = true,
                trigger = RecoveryDecisionTrigger.ICE_FAILED
            )
            return
        }

        val existing = edges[key]
        if (existing?.phase == EdgeRecoveryPhase.REATTACH_REQUESTED ||
            existing?.phase == EdgeRecoveryPhase.REATTACH_ACCEPTED ||
            existing?.phase == EdgeRecoveryPhase.ICE_RESTARTING
        ) {
            return
        }

        cancelDebounce(key)
        val existingBeforeDebounce = edges[key]
        if (needsNewObligationEpisode(existingBeforeDebounce)) {
            openNewRecoveryObligation(
                key,
                channelId,
                EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING,
                initiatesReattach,
                RecoveryDecisionTrigger.ICE_DISCONNECTED.name
            )
        } else {
            upsertEdge(
                key,
                channelId,
                EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING,
                initiatesReattach = initiatesReattach,
                attemptOpenTrigger = RecoveryDecisionTrigger.ICE_DISCONNECTED.name
            )
        }
        val debounce = scheduler.schedule({
            val current = edges[key]
            if (current?.hasActiveAttempt() == true &&
                current.phase != EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING
            ) {
                return@schedule
            }
            beginRecovery(
                key,
                channelId,
                eligibility,
                initiatesReattach,
                immediate = false,
                trigger = RecoveryDecisionTrigger.ICE_DISCONNECTED
            )
        }, debounceMs, TimeUnit.MILLISECONDS)
        debounceTimers[key] = debounce
    }

    /**
     * Connectivity-plane only. Callers from Membership / Join / Invite MUST NOT use this.
     * Illegal [RecoverySource] or [RecoveryReason] is rejected with NON_CONNECTIVITY_TRIGGER.
     */
    fun onRecoveryReattachAccepted(
        sessionId: String,
        remoteModuleId: String,
        recoveryReason: RecoveryReason = RecoveryReason.NETWORK_RECOVERY,
        source: RecoverySource = RecoverySource.ICE_MONITOR
    ) {
        if (!isConnectivityRecoverySource(source) || !isConnectivityRecoveryReason(recoveryReason)) {
            logRecoveryDecision(
                sessionId = sessionId,
                edge = remoteModuleId,
                trigger = RecoveryDecisionTrigger.REATTACH_ACCEPTED,
                recoveryReason = RecoveryReason.NON_CONNECTIVITY,
                terminationReason = RecoveryTerminationReason.UNKNOWN,
                policy = RecoveryDecisionPolicy.NO_RECOVERY,
                approved = false,
                rejectReason = "NON_CONNECTIVITY_TRIGGER",
                attempt = edges[ConferenceEdgeKey(sessionId, remoteModuleId)]?.recoveryAttemptId
            )
            return
        }
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val existing = edges[key]
        // Duplicate only when this attempt already accepted inbound reattach.
        // Host-owned ICE_RESTARTING without inbound accept MAY be superseded (ADR-0022 C-1.1 / #103003).
        if (existing?.recoveryViaInboundReattach == true &&
            existing.phase.isActivelyRecovering()
        ) {
            logRecoveryDecision(
                sessionId = sessionId,
                edge = remoteModuleId,
                trigger = RecoveryDecisionTrigger.REATTACH_ACCEPTED,
                recoveryReason = recoveryReason,
                terminationReason = RecoveryTerminationReason.UNKNOWN,
                policy = RecoveryDecisionPolicy.ICE_RESTART_ONLY,
                approved = false,
                rejectReason = "duplicate_reattach_accepted",
                attempt = existing.recoveryAttemptId
            )
            return
        }
        val record = existing ?: run {
            upsertEdge(
                key,
                channelId = "",
                phase = EdgeRecoveryPhase.REATTACH_ACCEPTED,
                initiatesReattach = false,
                attemptOpenTrigger = RecoveryDecisionTrigger.REATTACH_ACCEPTED.name,
                recoveryViaInboundReattach = true
            )
            edges[key]!!
        }
        if (isSessionCancelled(sessionId)) {
            onLog("RECOVERY_EVENT_DROPPED session=$sessionId remote=$remoteModuleId reason=session_cancelled")
            return
        }
        cancelDebounce(key)
        // #79 / ADR-0022 P1: ACCEPTED supersedes the prior attempt and cancels its watchdog.
        // New attempt owns a fresh budget starting at ICE-restarting / accepted lifecycle.
        if (existing != null) {
            val priorAttempt = record.recoveryAttemptId
            logHandoffToReattach(record, remoteModuleId, priorAttempt)
            supersedeAttempt(
                record,
                trigger = "REATTACH_INBOUND",
                scheduleNewWatchdog = false
            )
            onLog(
                "RECOVERY_DECISION session=$sessionId edge=$remoteModuleId " +
                    "attempt=${record.recoveryAttemptId} priorAttempt=$priorAttempt " +
                    "trigger=${RecoveryDecisionTrigger.REATTACH_ACCEPTED} " +
                    "decision=SUPERSEDED approved=true"
            )
        }
        record.phase = EdgeRecoveryPhase.REATTACH_ACCEPTED
        record.recoveryViaInboundReattach = true
        record.reattachDeliveryState = ReattachDeliveryState.ACCEPTED
        logPhaseTransition(record, existing?.phase, record.phase, "REATTACH_ACCEPTED")
        logRecoveryDecision(
            sessionId = sessionId,
            edge = remoteModuleId,
            trigger = RecoveryDecisionTrigger.REATTACH_ACCEPTED,
            recoveryReason = recoveryReason,
            terminationReason = RecoveryTerminationReason.UNKNOWN,
            policy = RecoveryDecisionPolicy.ICE_RESTART_ONLY,
            approved = true,
            rejectReason = null,
            attempt = record.recoveryAttemptId
        )
        onLog(
            "RECOVERY_REATTACH_ACCEPTED session=$sessionId remote=$remoteModuleId " +
                "attempt=${record.recoveryAttemptId} recoveryReason=$recoveryReason source=$source"
        )
        issueBoundedIceRestart(record, recoveryReason)
        // Soak gap (#83): ICE may already be CONNECTED with no fresh CONNECTED event.
        // Record the media fact for evaluation, but INV-NEG-016 / Q14: do not stamp
        // post-dispatch freshness from a probe of pre-existing ICE after restart dispatch.
        if (isIceConnected(sessionId, remoteModuleId)) {
            if (record.iceRestartIssued && record.restartDispatchAtMs != null) {
                record.mediaRestored = true
            } else {
                noteMediaRestored(record)
            }
            notifyAttemptLineageObservation(record, "transport_recovered_ice_connected")
            runIceRestorationCompletionEvaluation(record)
        }
        notifyChanged(sessionId)
    }

    @Deprecated("Use onRecoveryReattachAccepted 鈥?Membership must not call Recovery", ReplaceWith("onRecoveryReattachAccepted(sessionId, remoteModuleId, recoveryReason)"))
    fun onReattachAccepted(
        sessionId: String,
        remoteModuleId: String,
        recoveryReason: RecoveryReason = RecoveryReason.UNKNOWN
    ) {
        onRecoveryReattachAccepted(
            sessionId,
            remoteModuleId,
            recoveryReason,
            source = RecoverySource.JOIN_HANDLER
        )
    }

    fun onReattachRequested(sessionId: String, channelId: String, remoteModuleId: String) {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: return
        record.phase = EdgeRecoveryPhase.REATTACH_REQUESTED
        record.channelId = channelId
        onLog(
            "RECOVERY_REATTACH_REQUESTED session=$sessionId remote=$remoteModuleId " +
                "attempt=${record.recoveryAttemptId}"
        )
        notifyChanged(sessionId)
    }

    /**
     * Coordinator routing entry for outbound REATTACH reject payloads (ADR-0022 Appendix D / R28-L).
     * Parses [reasonPayload] and resolves lineage from the outbound dispatch snapshot on the edge.
     */
    fun onConferenceRecoveryReattachOutboundReject(
        sessionId: String,
        remoteModuleId: String,
        reasonPayload: String
    ): Boolean {
        val reason = OutboundReattachRejectReason.fromPayload(reasonPayload) ?: return false
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        val rejectedAttemptId = record.outboundDispatchAttemptId ?: record.recoveryAttemptId
        val rejectedObligationGeneration = record.outboundDispatchObligationGeneration
            ?: record.obligationGeneration
        return onRecoveryReattachOutboundRejected(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            rejectedAttemptId = rejectedAttemptId,
            rejectedObligationGeneration = rejectedObligationGeneration,
            reason = reason
        )
    }

    /**
     * Requester-side REATTACH reject (ADR-0022 R28-L INV-REC-007).
     * Reject is a reevaluate trigger 鈥?never direct [markRecovered].
     */
    fun onRecoveryReattachOutboundRejected(
        sessionId: String,
        remoteModuleId: String,
        rejectedAttemptId: Long,
        rejectedObligationGeneration: Long,
        reason: OutboundReattachRejectReason
    ): Boolean {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: return false
        if (rejectedAttemptId != record.recoveryAttemptId ||
            rejectedObligationGeneration != record.obligationGeneration
        ) {
            onLog(
                "STALE_REATTACH_REJECT_IGNORED session=$sessionId remote=$remoteModuleId " +
                    "reason=$reason rejectedAttempt=$rejectedAttemptId " +
                    "rejectedObligationGen=$rejectedObligationGeneration " +
                    "currentAttempt=${record.recoveryAttemptId} " +
                    "currentObligationGen=${record.obligationGeneration}"
            )
            return true
        }
        when (reason) {
            OutboundReattachRejectReason.OBLIGATION_CLOSED -> {
                record.reattachDeliveryState = ReattachDeliveryState.REJECTED
                onLog(
                    "RECOVERY_REATTACH_OUTBOUND_REJECTED session=$sessionId remote=$remoteModuleId " +
                        "reason=$reason attempt=${record.recoveryAttemptId} " +
                        "obligationGen=${record.obligationGeneration}"
                )
                onLog(
                    "RECOVERY_REEVALUATE_REQUIRED session=$sessionId edge=$remoteModuleId " +
                        "attempt=${record.recoveryAttemptId} trigger=REATtach_OUTBOUND_REJECTED " +
                        "rejectReason=$reason"
                )
                record.hasPendingCompletionDecision = true
                notifyChanged(sessionId)
            }
        }
        return true
    }

    fun onReattachRejected(
        sessionId: String,
        remoteModuleId: String,
        reason: String,
        recoverable: Boolean
    ) {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: return
        cancelDebounce(key)
        cancelWatchdog(key)
        record.phase = when {
            recoverable && !record.epochRefreshUsed -> {
                record.epochRefreshUsed = true
                EdgeRecoveryPhase.RECOVERY_PENDING
            }
            reason.contains("ENDPOINT", ignoreCase = true) ->
                EdgeRecoveryPhase.FAILED_IDENTITY_MISMATCH
            reason.contains("EPOCH", ignoreCase = true) ->
                EdgeRecoveryPhase.FAILED_STALE_LINEAGE
            else -> {
                enterFailedRequiresUserAction(record)
                EdgeRecoveryPhase.FAILED_REQUIRES_USER_ACTION
            }
        }
        onLog(
            "RECOVERY_REATTACH_REJECTED session=$sessionId remote=$remoteModuleId " +
                "reason=$reason phase=${record.phase}"
        )
        notifyChanged(sessionId)
    }

    fun onIceConnected(sessionId: String, remoteModuleId: String) {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: return
        cancelDebounce(key)
        // R28-H.2: debouncing is suspicion only 鈥?reconnect clears HEALTHY, never starts recovery / RECOVERED.
        if (record.phase == EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING) {
            clearDebouncingSuspicion(record)
            notifyChanged(sessionId)
            return
        }
        // Terminal monotonicity: late ICE after CLOSED(RECOVERED) must not reopen / rewrite phase
        // (soak gap2-casea: RECOVERED 鈫?controlPlaneStarted=false 鈫?ICE_RESTARTING poisoned UI).
        if (record.phase == EdgeRecoveryPhase.RECOVERED && !record.edgeObligationOpen()) {
            onLog(
                "IGNORE_LATE_ICE_AFTER_RECOVERED session=$sessionId remote=$remoteModuleId " +
                    "attempt=${record.recoveryAttemptId} obligationGen=${record.obligationGeneration} " +
                    "closeReason=${record.obligationCloseReason}"
            )
            return
        }
        // No open recovery obligation: idle CONNECTED bookkeeping only.
        if (!record.edgeObligationOpen()) {
            record.phase = EdgeRecoveryPhase.CONNECTED
            return
        }
        // ADR-0022 R28-E: record media fact, then completion evaluation 鈥?never direct RECOVERED.
        noteMediaRestored(record)
        notifyAttemptLineageObservation(record, "transport_recovered_on_ice_connected")
        runIceRestorationCompletionEvaluation(record)
    }

    /**
     * R28-H.2: ICE reconnects while still [EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING].
     * Clear suspicion 鈫?HEALTHY. MUST NOT beginRecovery / REATTACH / RECOVERED.
     */
    private fun clearDebouncingSuspicion(record: EdgeRecoveryRecord) {
        val key = record.key
        cancelDebounce(key)
        cancelWatchdog(key)
        cancelDeadline(key)
        record.phase = EdgeRecoveryPhase.CONNECTED
        clearMediaRestoredFact(record)
        record.iceRestartIssued = false
        record.restartDispatchAtMs = null
        record.obligationOpenedAtMs = null
        record.obligationDeadlineAtMs = null
        record.obligationClosedAtMs = null
        record.obligationCloseReason = null
        record.hasPendingCompletionDecision = false
        onLog(
            "RECOVERY_DEBOUNCE_CLEARED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "reason=ice_reconnected_before_attempt"
        )
    }

    /**
     * ICE restoration 鈫?completion evaluation (ADR-0022 R28-E / #83).
     * With [EdgeRecoveryRecord.controlPlaneStarted], ICE CONNECTED MAY yield RECOVERED.
     */
    private fun runIceRestorationCompletionEvaluation(record: EdgeRecoveryRecord) {
        val key = record.key
        val controlPlane = record.controlPlaneStarted()
        onLog(
            "RECOVERY_REEVALUATE session=${key.sessionId} edge=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                "controlPlaneStarted=$controlPlane mediaRestored=${record.mediaRestored}"
        )
        if (!record.mediaRestored) {
            onLog(
                "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                    "decision=NO_ACTION approved=true"
            )
            return
        }
        refreshControlReconciliationFact(record)
        if (record.phase.isFailedMediaRecovery() && record.edgeObligationOpen()) {
            reEvaluateContinuationAfterTerminal(record)
            return
        }
        // R28-E: before control-plane, keep the fact; do not complete the edge.
        // WAITING is not terminal 鈥?schedule control-plane continuation (ADR-0022).
        if (!controlPlane) {
            continueControlPlaneRecoveryAfterMediaRestored(record)
            return
        }
        if (!record.phase.isActivelyRecovering()) {
            if (record.phase.isFailedMediaRecovery() && record.edgeObligationOpen()) {
                reEvaluateContinuationAfterTerminal(record)
                return
            }
            onLog(
                "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                    "decision=NO_ACTION approved=true"
            )
            return
        }
        val snapshot = buildCompletionEvaluationSnapshot(record)
        if (tryCompletionFromFrozenPredicate(record, snapshot, RecoveryReevaluateTrigger.ICE_RESTORED)) {
            onLog(
                "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                    "decision=RECOVERED approved=true"
            )
        }
    }

    /**
     * Media path is restored but the attempt has not crossed the control-plane boundary.
     * MUST schedule a next action 鈥?never leave obligation OPEN with no owner (soak ea6466f1).
     *
     * REATTACH_THEN_ICE_RESTART (initiatesReattach): when E2 equivalent control-plane evidence
     * is already satisfied (reattach delivery + peer signaling path + media live), reuse the
     * same CONTROL_PLANE_BOUNDARY exit as ICE_RESTART_ONLY 鈥?do not wait forever for accept.
     */
    private fun continueControlPlaneRecoveryAfterMediaRestored(record: EdgeRecoveryRecord) {
        val key = record.key
        onLog(
            "RECOVERY_CONTROL_PLANE_REQUIRED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                "initiatesReattach=${record.initiatesReattach}"
        )
        if (record.initiatesReattach) {
            if (reattachMediaAlreadyLiveEvidenceSatisfied(record)) {
                crossControlPlaneBoundary(
                    record = record,
                    reason = "REATTACH_MEDIA_ALREADY_LIVE"
                )
                return
            }
            onLog(
                "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                    "decision=WAIT_FOR_CONTROL_PLANE approved=true"
            )
            // Route / inbound handlers own reattach dispatch 鈥?do not duplicate here.
            scheduleWatchdog(record)
            notifyChanged(key.sessionId)
            return
        }
        // ICE_RESTART_ONLY participant edge: do not flap transport when ICE is already CONNECTED.
        // Q13 B-3+B-1: media_path_active_without_restart is observation-only while a NEGOTIATION
        // deferred ICE-restart intent remains uncovered 鈥?forbid short-circuit into ICE_RESTARTING.
        if (isIceConnected(key.sessionId, key.remoteModuleId) && record.mediaRestored) {
            if (isDeferredIceRestartIntent(record)) {
                onLog(
                    "RECOVERY_MEDIA_PATH_OBSERVATION session=${key.sessionId} remote=${key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId} reason=media_path_active_without_restart " +
                        "decision=HOLD pendingNegotiationDefer=true " +
                        "intentId=${record.iceRestartIntentId ?: "NONE"} " +
                        "gateBlock=${record.deferredGateBlockReason ?: "UNKNOWN"}"
                )
                onLog(
                    "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                        "decision=WAIT_FOR_NEGOTIATION_INTENT approved=true"
                )
                notifyChanged(key.sessionId)
                return
            }
            crossControlPlaneBoundary(
                record = record,
                reason = "media_path_active_without_restart"
            )
            return
        }
        onLog(
            "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                "decision=WAIT_FOR_CONTROL_PLANE approved=true"
        )
        issueBoundedIceRestart(record, RecoveryReason.ICE_DISCONNECTED)
    }

    /**
     * E2 equivalent control-plane convergence for REATTACH_THEN_ICE_RESTART (ADR-0022 4.3-D).
     * Identity is implicit: [record] is the live edge map entry for the current attempt/gen.
     * MUST NOT treat ICE_CONNECTED / mediaRestored alone as control-plane started.
     */
    private fun reattachMediaAlreadyLiveEvidenceSatisfied(record: EdgeRecoveryRecord): Boolean {
        if (!record.edgeObligationOpen()) return false
        if (!record.initiatesReattach) return false
        if (!hasReattachDeliveryEvidence(record)) return false
        val key = record.key
        // Peer signaling path reachable (authority/peer plane via existing action gate).
        if (!canDispatchRecoveryMediaAction(key.sessionId, key.remoteModuleId)) return false
        if (!record.mediaRestored) return false
        if (!isIceConnected(key.sessionId, key.remoteModuleId)) return false
        return true
    }

    private fun hasReattachDeliveryEvidence(record: EdgeRecoveryRecord): Boolean =
        record.reattachDeliveryState == ReattachDeliveryState.TRANSPORT_SENT ||
            record.reattachDeliveryState == ReattachDeliveryState.REMOTE_RECEIPT_ACKED

    /** Shared CONTROL_PLANE_BOUNDARY exit (H1/H3): phase + log + existing completion evaluator. */
    private fun crossControlPlaneBoundary(record: EdgeRecoveryRecord, reason: String) {
        val key = record.key
        record.phase = EdgeRecoveryPhase.ICE_RESTARTING
        onLog(
            "RECOVERY_CONTROL_PLANE_BOUNDARY session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} reason=$reason"
        )
        runIceRestorationCompletionEvaluation(record)
    }

    fun cancelSession(sessionId: String, reason: String) {
        cancelledSessions[sessionId] = clock() + tombstoneTtlMs
        edges.keys.filter { it.sessionId == sessionId }.forEach { cancelEdge(it, reason) }
        notifyChanged(sessionId)
    }

    fun cancelChannel(channelId: String, reason: String) {
        cancelledChannels[channelId] = clock() + tombstoneTtlMs
        edges.values.filter { it.channelId == channelId }.forEach { cancelEdge(it.key, reason) }
        edges.keys
            .filter { key -> edges[key]?.channelId == channelId }
            .forEach { cancelEdge(it, reason) }
    }

    fun cancelEdge(sessionId: String, remoteModuleId: String, reason: String) {
        cancelEdge(ConferenceEdgeKey(sessionId, remoteModuleId), reason)
    }

    fun clearAll() {
        debounceTimers.values.forEach { it.cancel(false) }
        debounceTimers.clear()
        watchdogTimers.values.forEach { it.cancel(false) }
        watchdogTimers.clear()
        deadlineTimers.values.forEach { it.cancel(false) }
        deadlineTimers.clear()
        edges.clear()
        terminalReevaluateDedup.clear()
        cancelledSessions.clear()
        cancelledChannels.clear()
        deferredIntentAuthority.clearAll()
    }

    /**
     * Slice-1: before media-domain re-admission clears deferral fields, committed negotiation
     * intents must pass through DeferredIntentAuthority.requestSupersede (no silent clear).
     */
    private fun supersedeCommittedNegotiationIntentIfPresent(
        record: EdgeRecoveryRecord,
        reason: String
    ) {
        val intentId = record.iceRestartIntentId ?: return
        if (deferredIntentAuthority.executionState(intentId) == null) {
            val fenceArmed =
                Pr52cDebugInjection.fencedIntentId(record.key.sessionId, record.key.remoteModuleId) ==
                    intentId ||
                    record.deferredReason == DeferredReason.NEGOTIATION_SETTLING
            if (!fenceArmed && record.deferredIntentHoldReason == null) return
            deferredIntentAuthority.registerCreated(
                intentId = intentId,
                sessionId = record.key.sessionId,
                remoteModuleId = record.key.remoteModuleId,
                fenceArmed = fenceArmed
            )
        }
        if (record.deferredIntentHoldReason == DeferredIntentHoldReason.DISPATCH) {
            deferredIntentAuthority.markHeldDispatch(intentId)
        }
        deferredIntentAuthority.requestSupersede(
            intentId = intentId,
            reason = reason,
            requestingDomain = DeferredIntentAuthority.RequestingDomain.MEDIA
        )
    }

    private fun beginRecovery(
        key: ConferenceEdgeKey,
        channelId: String,
        eligibility: EdgeRecoveryEligibility,
        initiatesReattach: Boolean,
        immediate: Boolean,
        trigger: RecoveryDecisionTrigger
    ) {
        if (!eligibility.isEligible()) {
            val terminationReason = inferTerminationReason(eligibility, trigger)
            val recoveryReason = resolveRecoveryReason(trigger, initiatesReattach)
            val rejectReason = ineligibilityReason(eligibility)
            logRecoveryDecision(
                sessionId = key.sessionId,
                edge = key.remoteModuleId,
                trigger = trigger,
                recoveryReason = recoveryReason,
                terminationReason = terminationReason,
                policy = RecoveryDecisionPolicy.NO_RECOVERY,
                approved = false,
                rejectReason = rejectReason,
                attempt = edges[key]?.recoveryAttemptId
            )
            onLog(
                "RECOVERY_EDGE_SKIPPED session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "reason=ineligible immediate=$immediate"
            )
            return
        }
        val existing = edges[key]
        if (existing?.hasActiveAttempt() == true &&
            existing.phase != EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING
        ) {
            onLog(
                "RECOVERY_ATTEMPT_REUSED session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "attempt=${existing.recoveryAttemptId} trigger=$trigger " +
                    "phase=${existing.phase} existingOwnerRetained=true"
            )
            return
        }
        val record = when {
            needsNewObligationEpisode(existing) &&
                existing?.phase != EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING -> {
                openNewRecoveryObligation(
                    key,
                    channelId,
                    EdgeRecoveryPhase.RECOVERY_PENDING,
                    initiatesReattach,
                    trigger.name
                )
            }
            else -> {
                upsertEdge(
                    key,
                    channelId,
                    EdgeRecoveryPhase.RECOVERY_PENDING,
                    initiatesReattach = initiatesReattach,
                    newAttempt = existing == null,
                    attemptOpenTrigger = trigger.name
                )
            }
        }
        val policy = if (initiatesReattach) {
            RecoveryDecisionPolicy.REATTACH_THEN_ICE_RESTART
        } else {
            RecoveryDecisionPolicy.ICE_RESTART_ONLY
        }
        val recoveryReason = resolveRecoveryReason(trigger, initiatesReattach)
        logRecoveryDecision(
            sessionId = key.sessionId,
            edge = key.remoteModuleId,
            trigger = trigger,
            recoveryReason = recoveryReason,
            terminationReason = RecoveryTerminationReason.NETWORK_LOSS,
            policy = policy,
            approved = true,
            rejectReason = null,
            attempt = record.recoveryAttemptId
        )
        onLog(
            "RECOVERY_EDGE_STARTED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} initiatesReattach=$initiatesReattach " +
                "immediate=$immediate recoveryReason=$recoveryReason"
        )
        record.mediaActionOwner = MediaActionOwner.PENDING
        // J-X Slice-1: MEDIA EDGE_STARTED must not silently wipe committed NEGOTIATION intent.
        supersedeCommittedNegotiationIntentIfPresent(
            record,
            reason = "EDGE_STARTED:${trigger.name}"
        )
        releaseDeferredIntentSlot(
            record = record,
            reason = "EDGE_STARTED:${trigger.name}",
            domain = DeferredIntentAuthority.RequestingDomain.MEDIA,
            kind = DeferredIntentAuthority.ReleaseKind.SUPERSEDE
        )
        clearDeferralFields(record)
        if (initiatesReattach) {
            applyReattachDispatchOutcome(
                record = record,
                outcome = onRequestReattach(key.sessionId, channelId, key.remoteModuleId)
            )
        } else {
            resolveMediaActionOwner(
                record = record,
                recoveryReason = recoveryReason,
                immediate = immediate,
                trigger = trigger.name
            )
        }
        scheduleWatchdog(record)
        notifyChanged(key.sessionId)
    }

    private fun issueBoundedIceRestart(
        record: EdgeRecoveryRecord,
        recoveryReason: RecoveryReason = RecoveryReason.UNKNOWN
    ) {
        if (record.iceRestartIssued) {
            logRecoveryDecision(
                sessionId = record.key.sessionId,
                edge = record.key.remoteModuleId,
                trigger = RecoveryDecisionTrigger.ICE_RESTART,
                recoveryReason = recoveryReason,
                terminationReason = RecoveryTerminationReason.UNKNOWN,
                policy = RecoveryDecisionPolicy.ICE_RESTART_ONLY,
                approved = false,
                rejectReason = "duplicate_ice_restart",
                attempt = record.recoveryAttemptId
            )
            return
        }
        // Negotiation Stabilization Gate (INV-NEG-006): sole execution admission point.
        // DEFER keeps phase / iceRestartIssued / watchdog unchanged (INV-NEG-004).
        val probe = probeIceRestartGate(record.key.sessionId, record.key.remoteModuleId)
        if (!probe.executable) {
            val block = probe.blockReason ?: IceRestartGateBlockReason.SIGNALING_NOT_STABLE
            val intentId = allocateIceRestartIntentId(record)
            onLog(
                "ICE_RESTART_GATE_BLOCKED session=${record.key.sessionId} " +
                    "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                    "intentId=$intentId reason=$block " +
                    "signalingState=${probe.signalingState ?: "UNKNOWN"} " +
                    "localRole=${probe.localRole ?: "UNKNOWN"}"
            )
            recordMediaActionDeferred(
                record = record,
                owner = MediaActionOwner.HOST_RESTART,
                reason = DeferredReason.NEGOTIATION_SETTLING,
                wakeupBinding = WakeupBinding(
                    sourceType = WakeupSourceType.NEGOTIATION_CAN_EXECUTE,
                    sourceKey = edgeWakeupKey(record.key.sessionId, record.key.remoteModuleId)
                ),
                trigger = "NEGOTIATION_STABILIZATION_GATE:$block"
            )
            record.deferredGateBlockReason = block
            onLog(
                "ICE_RESTART_DEFERRED session=${record.key.sessionId} " +
                    "remote=${record.key.remoteModuleId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} gen=${record.obligationGeneration} " +
                    "intentId=$intentId reason=$block wakeup=NEGOTIATION_CAN_EXECUTE"
            )
            // INV-NEG-015 / INV-NEG-020: baseline before waiting; DEFER_ADMISSION may recompute.
            // bindAdmissionSeq MUST run before rising-edge drain inside the Coordinator callback.
            onNegotiationGateDeferred(record.key.sessionId, record.key.remoteModuleId) { seq ->
                record.deferAdmissionObservationSeq = seq
                onLog(
                    "DEFERRED_INTENT_CREATED session=${record.key.sessionId} " +
                        "remote=${record.key.remoteModuleId} intentId=$intentId " +
                        "attemptId=${record.recoveryAttemptId} " +
                        "obligationGen=${record.obligationGeneration} " +
                        "baselineCapability=false admissionSeq=$seq " +
                        "gateBlock=$block"
                )
                // J-X-3: committed negotiation intent starts ARMED; supersede releases
                // via RELEASED_BY_SUPERSEDE (debug validation fence is an additional overlay).
                deferredIntentAuthority.registerCreated(
                    intentId = intentId,
                    sessionId = record.key.sessionId,
                    remoteModuleId = record.key.remoteModuleId,
                    fenceArmed = true
                )
                observeNegotiationOwner(record, "DEFER_ADMISSION")
                RecoveryNegotiationObservation.emitIntent(
                    sessionId = record.key.sessionId,
                    edgeModuleId = record.key.remoteModuleId,
                    intentId = intentId,
                    episodeId = record.recoveryAttemptId,
                    ownerModuleId = localModuleId,
                    reason = block.name,
                    state = RecoveryNegotiationObservation.IntentState.CREATED
                )
            }
            return
        }
        val admissionProjection = evaluateRecoveryAdmission(
            record.key.sessionId,
            record.key.remoteModuleId
        )
        if (admissionProjection.decision != AdmissionDecisionProjection.DISPATCH_NOW) {
            val waitingReason = admissionProjection.toRecoveryWaitingReason()
            onLog(
                "RECOVERY_WAITING session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "reason=${waitingReason ?: admissionProjection.decision} " +
                    "confidence=${admissionProjection.confidence} " +
                    "admissionReason=${admissionProjection.reason} " +
                    "lastInboundAgeMs=${admissionProjection.lastInboundAgeMs}"
            )
            recordMediaActionDeferred(
                record = record,
                owner = MediaActionOwner.HOST_RESTART,
                reason = DeferredReason.MEDIA_NOT_READY,
                wakeupBinding = WakeupBinding(
                    sourceType = WakeupSourceType.ROUTE_CONVERGED,
                    sourceKey = edgeWakeupKey(record.key.sessionId, record.key.remoteModuleId)
                ),
                trigger = "ADMISSION_CONFIDENCE:${admissionProjection.decision}"
            )
            return
        }
        val negotiationOwner = ensureCanonicalNegotiationOwner(record, "ICE_RESTART_DISPATCH")
        if (negotiationOwner != localModuleId) {
            onLog(
                "NEGOTIATION_NON_OWNER_BLOCKED session=${record.key.sessionId} " +
                    "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                    "owner=$negotiationOwner local=$localModuleId"
            )
            return
        }
        record.phase = EdgeRecoveryPhase.ICE_RESTARTING
        record.iceRestartIssued = true
        record.restartDispatchAtMs = clock()
        assignMediaActionOwner(record, MediaActionOwner.HOST_RESTART)
        onLog(
            "RECOVERY_ICE_RESTART_DISPATCHED session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                "intentId=${record.iceRestartIntentId ?: "NONE"} " +
                "restartDispatchAtMs=${record.restartDispatchAtMs}"
        )
        val restarted = onIceRestart(record.key.sessionId, record.key.remoteModuleId)
        if (!restarted) {
            // Restart API may fail while ICE is already CONNECTED (#83 soak). Keep the
            // attempt active so completion evaluation can still observe mediaRestored.
            // INV-NEG-016: do not stamp a fresh mediaRestoredObservedAtMs from pre-existing ICE.
            if (isIceConnected(record.key.sessionId, record.key.remoteModuleId)) {
                record.mediaRestored = true
            } else {
                enterFailedMediaResidency(record, reason = "ice_restart_failed")
            }
        }
        scheduleWatchdog(record)
        notifyChanged(record.key.sessionId)
    }

    private fun scheduleWatchdog(record: EdgeRecoveryRecord) {
        val key = record.key
        val attemptId = record.recoveryAttemptId
        val obligationGen = record.obligationGeneration
        cancelWatchdog(key)
        if (isCapabilityBlockingAttemptClock(record)) {
            onLog(
                "RECOVERY_WATCHDOG_DEFERRED session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "obligationGen=$obligationGen attempt=$attemptId " +
                    "reason=CAPABILITY_UNAVAILABLE " +
                    "deferredReason=${record.deferredReason ?: "dispatch_gate"}"
            )
            return
        }
        val budgetMs = minOf(attemptBudgetMs, iceRestartTimeoutMs + debounceMs)
        onLog(
            "RECOVERY_WATCHDOG_STARTED session=${key.sessionId} edge=${key.remoteModuleId} " +
                "obligationGen=$obligationGen attempt=$attemptId budgetMs=$budgetMs"
        )
        val future = scheduler.schedule({
            val current = edges[key] ?: return@schedule
            if (current.recoveryAttemptId != attemptId) return@schedule
            if (current.obligationGeneration != obligationGen) return@schedule
            if (!current.phase.isActivelyRecovering()) return@schedule
            onLog(
                "RECOVERY_FINAL_EVALUATION session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "attempt=${current.recoveryAttemptId} obligationGen=${current.obligationGeneration} " +
                    "reason=ATTEMPT_TIMEOUT controlPlaneStarted=${current.controlPlaneStarted()}"
            )
            onLog(
                "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "attempt=${current.recoveryAttemptId} obligationGen=${current.obligationGeneration} " +
                    "decision=ATTEMPT_TIMEOUT approved=false"
            )
            onLog(
                "RECOVERY_ATTEMPT_TIMEOUT session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "obligationGen=${current.obligationGeneration} attempt=${current.recoveryAttemptId}"
            )
            // Re-check after logging: ACCEPTED may have SUPERSEDED mid-callback (TOCTOU).
            val still = edges[key] ?: return@schedule
            if (still.recoveryAttemptId != attemptId) return@schedule
            if (still.obligationGeneration != obligationGen) return@schedule
            if (!still.phase.isActivelyRecovering()) return@schedule
            if (isCapabilityBlockingAttemptClock(still)) {
                onLog(
                    "RECOVERY_WATCHDOG_DEFERRED session=${key.sessionId} edge=${key.remoteModuleId} " +
                        "obligationGen=${still.obligationGeneration} attempt=${still.recoveryAttemptId} " +
                        "reason=CAPABILITY_UNAVAILABLE_AT_FIRE " +
                        "deferredReason=${still.deferredReason ?: "dispatch_gate"}"
                )
                return@schedule
            }
            if (shouldDeferWatchdogForMembershipConvergence(still)) {
                emitRecoveryCompletionBlocked(still, "MEMBERSHIP_CONVERGENCE_PENDING")
                onLog(
                    "RECOVERY_WATCHDOG_DEFERRED session=${key.sessionId} edge=${key.remoteModuleId} " +
                        "obligationGen=${still.obligationGeneration} attempt=${still.recoveryAttemptId} " +
                        "reason=MEMBERSHIP_CONVERGENCE_PENDING"
                )
                scheduleWatchdog(still)
                return@schedule
            }
            if (shouldDeferWatchdogForControlReconciliation(still)) {
                emitRecoveryCompletionBlocked(still, "CONTROL_RECONCILIATION_PENDING")
                onLog(
                    "RECOVERY_WATCHDOG_DEFERRED session=${key.sessionId} edge=${key.remoteModuleId} " +
                        "obligationGen=${still.obligationGeneration} attempt=${still.recoveryAttemptId} " +
                        "reason=CONTROL_RECONCILIATION_PENDING"
                )
                scheduleWatchdog(still)
                return@schedule
            }
            val mediaSatisfied = still.mediaRestored ||
                isIceConnected(key.sessionId, key.remoteModuleId)
            val controlFact = still.controlReconciliationFact?.takeIf { it.isCurrentFor(still) }
            if (mediaSatisfied && controlFact?.result == true) {
                val snapshot = EdgeReachabilitySnapshot(
                    linkReady = true,
                    peerDiscovered = true,
                    peerSignalingReachable = true,
                    mediaRouteConnected = still.mediaRestored,
                    authorityReachable = true
                )
                if (tryCompletionFromFrozenPredicate(still, snapshot, RecoveryReevaluateTrigger.ICE_RESTORED)) {
                    onLog(
                        "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                            "attempt=${still.recoveryAttemptId} trigger=ATTEMPT_TIMEOUT " +
                            "decision=RECOVERED approved=true"
                    )
                    notifyChanged(key.sessionId)
                    return@schedule
                }
            }
            val abortReason = when {
                hasDeferredMediaAction(still) -> {
                    assignMediaActionOwner(still, MediaActionOwner.ABORTED)
                    "OWNER_BLOCKED"
                }
                !still.mediaActionOwner.isAssigned() -> {
                    assignMediaActionOwner(still, MediaActionOwner.ABORTED)
                    "NO_MEDIA_ACTION_OWNER"
                }
                else -> "attempt_timeout"
            }
            val failureClass = when {
                abortReason == "NO_MEDIA_ACTION_OWNER" || abortReason == "OWNER_BLOCKED" ->
                    RecoveryFailureClass.EXPLICIT_ABORT
                else -> classifyRecoveryFailureAtTimeout(still)
            }
            enterFailedMediaResidency(
                still,
                reason = abortReason,
                explicitAbort = abortReason == "NO_MEDIA_ACTION_OWNER" || abortReason == "OWNER_BLOCKED",
                failureClass = failureClass
            )
            notifyChanged(key.sessionId)
        }, budgetMs, TimeUnit.MILLISECONDS)
        watchdogTimers[key] = future
    }

    private fun cancelEdge(key: ConferenceEdgeKey, reason: String) {
        cancelDebounce(key)
        cancelWatchdog(key)
        cancelDeadline(key)
        val record = edges[key] ?: return
        recoveryOfferDeliveryPolicy.cancel(record)
        record.phase = EdgeRecoveryPhase.CANCELLED
        val closeReason = when {
            reason.contains("session_cancelled", ignoreCase = true) ||
                reason.contains("conference", ignoreCase = true) ||
                reason.contains("terminated", ignoreCase = true) ->
                ObligationCloseReason.CONFERENCE_TERMINATED
            else -> ObligationCloseReason.MEMBERSHIP_LEFT
        }
        RecoveryCompletionPolicy.closeObligation(completionMutationHost, record, closeReason, reason)
        onLog(
            "RECOVERY_EDGE_CANCELLED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "reason=$reason"
        )
        edges.remove(key)
    }

    private fun resolveRecoveryInitiator(initiatesReattach: Boolean): String =
        if (initiatesReattach) "PARTICIPANT" else "AUTHORITY"

    private fun resolveRecoveryPolicy(initiatesReattach: Boolean): String =
        if (initiatesReattach) {
            RecoveryDecisionPolicy.REATTACH_THEN_ICE_RESTART.name
        } else {
            RecoveryDecisionPolicy.ICE_RESTART_ONLY.name
        }

    private fun cancelDebounce(key: ConferenceEdgeKey) {
        debounceTimers.remove(key)?.cancel(false)
    }

    private fun cancelWatchdog(key: ConferenceEdgeKey) {
        watchdogTimers.remove(key)?.cancel(false)
    }

    private fun upsertEdge(
        key: ConferenceEdgeKey,
        channelId: String,
        phase: EdgeRecoveryPhase,
        initiatesReattach: Boolean,
        newAttempt: Boolean = false,
        attemptOpenTrigger: String? = null,
        recoveryViaInboundReattach: Boolean = false
    ): EdgeRecoveryRecord {
        val now = clock()
        val existing = edges[key]
        val record = if (existing == null || newAttempt) {
            val previousAttempt = existing?.recoveryAttemptId
            val previousPhase = existing?.phase
            val previousObligationOpen = existing?.obligationClosedAtMs == null &&
                existing?.obligationOpenedAtMs != null
            // While OPEN, preserve obligation facts across attempts. After CLOSED, a later
            // recovery cycle starts a new obligation (not a reopen of the closed one).
            val preserveOpen = existing != null && existing.obligationClosedAtMs == null
            val obligationGen = when {
                preserveOpen -> existing!!.obligationGeneration
                existing == null -> 1L
                else -> existing.obligationGeneration + 1L
            }
            EdgeRecoveryRecord(
                key = key,
                phase = phase,
                channelId = channelId,
                recoveryAttemptId = ++attemptSeq,
                recoveryStartedAtMs = now,
                initiatesReattach = initiatesReattach,
                recoveryViaInboundReattach = recoveryViaInboundReattach,
                obligationGeneration = obligationGen,
                obligationOpenedAtMs = if (preserveOpen) {
                    existing!!.obligationOpenedAtMs ?: now
                } else {
                    now
                },
                obligationDeadlineAtMs = if (preserveOpen) existing!!.obligationDeadlineAtMs else null,
                obligationClosedAtMs = null,
                obligationCloseReason = null,
                hasPendingCompletionDecision = if (preserveOpen) {
                    existing!!.hasPendingCompletionDecision
                } else {
                    false
                }
            ).also { created ->
                edges[key] = created
                val trigger = attemptOpenTrigger
                    ?: if (newAttempt) "NEW_ATTEMPT" else "UPSERT"
                ensureCanonicalNegotiationOwner(created, trigger)
                val pathway = when {
                    newAttempt -> "BEGIN_RECOVERY"
                    existing == null -> "UPSERT_EDGE"
                    else -> "NEW_ATTEMPT"
                }
                onLog(
                    formatRecoveryAttemptOpenedLog(
                        sessionId = key.sessionId,
                        remoteModuleId = key.remoteModuleId,
                        attemptId = created.recoveryAttemptId,
                        initiator = resolveRecoveryInitiator(initiatesReattach),
                        policy = resolveRecoveryPolicy(initiatesReattach),
                        startedAt = created.recoveryStartedAtMs,
                        supersededFromAttempt = null,
                        reason = trigger,
                        previousAttempt = previousAttempt,
                        previousPhase = previousPhase,
                        obligationOpen = previousObligationOpen,
                        obligationGeneration = created.obligationGeneration,
                        pathway = pathway
                    )
                )
                logPhaseTransition(created, existing?.phase, created.phase, if (newAttempt) "NEW_ATTEMPT" else "UPSERT")
            }
        } else {
            existing.apply {
                val oldPhase = this.phase
                this.phase = phase
                if (oldPhase != phase) {
                    logPhaseTransition(this, oldPhase, phase, "UPSERT")
                }
                if (channelId.isNotBlank()) this.channelId = channelId
                this.initiatesReattach = initiatesReattach
                if (obligationOpenedAtMs == null) {
                    obligationOpenedAtMs = now
                    if (obligationGeneration == 0L) obligationGeneration = 1L
                }
            }
        }
        return record
    }

    private fun notifyChanged(sessionId: String) {
        onRecoveryStateChanged(sessionId)
    }

    private fun resolveRecoveryReason(
        trigger: RecoveryDecisionTrigger,
        initiatesReattach: Boolean
    ): RecoveryReason = when {
        initiatesReattach -> RecoveryReason.HOST_REATTACH
        trigger == RecoveryDecisionTrigger.ICE_FAILED -> RecoveryReason.ICE_FAILED
        trigger == RecoveryDecisionTrigger.ICE_DISCONNECTED -> RecoveryReason.ICE_DISCONNECTED
        else -> RecoveryReason.NETWORK_RECOVERY
    }

    private fun isConnectivityRecoverySource(source: RecoverySource): Boolean = when (source) {
        RecoverySource.ICE_MONITOR,
        RecoverySource.TRANSPORT_MONITOR,
        RecoverySource.RECOVERY_TIMER -> true
        RecoverySource.JOIN_HANDLER,
        RecoverySource.INVITE_HANDLER,
        RecoverySource.USER_ACTION -> false
    }

    private fun isConnectivityRecoveryReason(reason: RecoveryReason): Boolean = when (reason) {
        RecoveryReason.NETWORK_RECOVERY,
        RecoveryReason.HOST_REATTACH,
        RecoveryReason.ICE_FAILED,
        RecoveryReason.ICE_DISCONNECTED -> true
        RecoveryReason.SESSION_CANCELLED,
        RecoveryReason.NON_CONNECTIVITY,
        RecoveryReason.UNKNOWN -> false
    }

    private fun inferTerminationReason(
        eligibility: EdgeRecoveryEligibility,
        trigger: RecoveryDecisionTrigger
    ): RecoveryTerminationReason = when {
        eligibility.conferenceTerminated -> RecoveryTerminationReason.CONFERENCE_TERMINATED
        !eligibility.remoteJoined || !eligibility.localJoined ->
            RecoveryTerminationReason.USER_LEAVE
        !eligibility.lifecycleEstablished -> RecoveryTerminationReason.NOT_ESTABLISHED
        trigger == RecoveryDecisionTrigger.ICE_DISCONNECTED ||
            trigger == RecoveryDecisionTrigger.ICE_FAILED ->
            RecoveryTerminationReason.NETWORK_LOSS
        else -> RecoveryTerminationReason.UNKNOWN
    }

    private fun ineligibilityReason(eligibility: EdgeRecoveryEligibility): String = when {
        eligibility.conferenceTerminated -> "conference_terminated"
        !eligibility.lifecycleEstablished -> "lifecycle_not_established"
        !eligibility.localJoined -> "local_not_joined"
        !eligibility.remoteJoined -> "remote_not_joined"
        else -> "ineligible"
    }

    private fun logRecoveryDecision(
        sessionId: String,
        edge: String,
        trigger: RecoveryDecisionTrigger,
        recoveryReason: RecoveryReason,
        terminationReason: RecoveryTerminationReason,
        policy: RecoveryDecisionPolicy,
        approved: Boolean,
        rejectReason: String?,
        attempt: Long?
    ) {
        val attemptPart = attempt?.let { "attempt=$it" } ?: "attempt=-"
        val rejectPart = rejectReason?.let { " rejectReason=$it" } ?: ""
        onLog(
            "RECOVERY_DECISION session=$sessionId edge=$edge trigger=$trigger " +
                "recoveryReason=$recoveryReason terminationReason=$terminationReason " +
                "policy=$policy approved=$approved $attemptPart$rejectPart"
        )
    }

    private fun buildCompletionEvaluationSnapshot(record: EdgeRecoveryRecord): EdgeReachabilitySnapshot {
        val key = record.key
        val iceConnected = isIceConnected(key.sessionId, key.remoteModuleId)
        return EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = canDispatchRecoveryMediaAction(key.sessionId, key.remoteModuleId),
            mediaRouteConnected = iceConnected || record.mediaRestored,
            authorityReachable = true
        )
    }

    private fun tryCompletionFromFrozenPredicate(
        record: EdgeRecoveryRecord,
        snapshot: EdgeReachabilitySnapshot,
        trigger: RecoveryReevaluateTrigger,
        evidence: String? = null,
        skipMembershipRefresh: Boolean = false
    ): Boolean {
        if (!skipMembershipRefresh) {
            refreshControlReconciliationFact(record)
        }
        val key = record.key
        val iceConnectedProbe = isIceConnected(key.sessionId, key.remoteModuleId)
        val iceConnectedForPredicate =
            iceConnectedProbe || trigger == RecoveryReevaluateTrigger.ICE_RESTORED
        val mediaUnavailableAdvisory =
            isMediaUnavailable(key.sessionId, key.remoteModuleId) ||
                (iceConnectedForPredicate && !record.mediaRestored && !snapshot.mediaRouteConnected)
        val observation = RecoveryCompletionPolicy.evaluate(
            record = record,
            snapshot = snapshot,
            iceConnected = iceConnectedForPredicate,
            mediaUnavailableAdvisory = mediaUnavailableAdvisory,
            hasUncoveredDeferredIntent = hasDeferredMediaAction(record)
        )
        RecoveryCompletionPolicy.logCompletionDecision(completionMutationHost, observation, trigger)
        if (!CompletionObservationProjection.controlReconciliationCompleted(record)) return false
        if (observation.candidate != CompletionObservationProjection.CompletionCandidate.RECOVERED) {
            return false
        }
        val closeEvidence = evidence ?: completionEvidenceFromReachability(record, snapshot, trigger)
        logCompletionEvidenceAccepted(record, closeEvidence, snapshot)
        return RecoveryCompletionPolicy.markRecovered(completionMutationHost, record, closeEvidence)
    }

    /** P2-B re-evaluate completion evaluation (ADR-0022 R28-C/E). */
    private fun runCompletionEvaluationStub(
        record: EdgeRecoveryRecord,
        snapshot: EdgeReachabilitySnapshot,
        signature: RecoveryCapabilitySignature,
        trigger: RecoveryReevaluateTrigger,
        skipMembershipRefresh: Boolean = false
    ) {
        if (tryCompletionFromFrozenPredicate(
                record,
                snapshot,
                trigger,
                skipMembershipRefresh = skipMembershipRefresh
            )
        ) {
            onLog(
                "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=$trigger decision=RECOVERED approved=true"
            )
            return
        }
        if (record.phase.isFailedMediaRecovery() && hasResurrectionEvidence(snapshot, trigger)) {
            if (!admitTerminalReevaluate(record, trigger)) {
                onLog(
                    "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId} trigger=$trigger decision=IGNORE " +
                        "approved=true rejectReason=duplicate_post_terminal_fact"
                )
                return
            }
            val priorAttempt = record.recoveryAttemptId
            supersedeFailedResidencyAndAdmit(record, trigger, snapshot, signature)
            onLog(
                "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} priorAttempt=$priorAttempt " +
                    "trigger=$trigger decision=SUPERSEDED approved=true"
            )
            notifyChanged(record.key.sessionId)
            return
        }
        if (record.phase.isFailedMediaRecovery() && signature.permittedActions.isNotEmpty()) {
            val priorAttempt = record.recoveryAttemptId
            supersedeFailedResidencyAndAdmit(record, trigger, snapshot, signature)
            onLog(
                "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} priorAttempt=$priorAttempt " +
                    "trigger=$trigger decision=SUPERSEDED approved=true"
            )
            notifyChanged(record.key.sessionId)
            return
        }
        signature.waitingReason?.let { reason ->
            onLog(
                "RECOVERY_WAITING session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "reason=$reason ${snapshot.formatProbeFields()}"
            )
        }
        if (RecoveryAction.DISPATCH_REATTACH in signature.permittedActions) {
            when {
                !record.initiatesReattach -> {
                    onLog(
                        "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                            "attempt=${record.recoveryAttemptId} trigger=$trigger " +
                            "decision=WAIT_FOR_INBOUND approved=true"
                    )
                    notifyChanged(record.key.sessionId)
                    return
                }
                record.controlPlaneStarted() -> {
                    onLog(
                        "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                            "attempt=${record.recoveryAttemptId} trigger=$trigger " +
                            "decision=DISPATCH_REATTACH approved=false rejectReason=control_plane_started"
                    )
                    return
                }
                record.phase == EdgeRecoveryPhase.REATTACH_REQUESTED -> {
                    onLog(
                        "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                            "attempt=${record.recoveryAttemptId} trigger=$trigger " +
                            "decision=DISPATCH_REATTACH approved=false rejectReason=transport_in_flight"
                    )
                    return
                }
                else -> {
                    applyReattachDispatchOutcome(
                        record = record,
                        outcome = onRequestReattach(
                            record.key.sessionId,
                            record.channelId,
                            record.key.remoteModuleId
                        ),
                        trigger = trigger
                    )
                    notifyChanged(record.key.sessionId)
                    return
                }
            }
        }
        if (signature.permittedActions.isEmpty() && signature.waitingReason != null) {
            if (
                !record.initiatesReattach &&
                record.phase.isActivelyRecovering() &&
                (record.mediaActionOwner == MediaActionOwner.PENDING || hasDeferredMediaAction(record))
            ) {
                resolveMediaActionOwner(
                    record = record,
                    recoveryReason = RecoveryReason.NETWORK_RECOVERY,
                    immediate = false,
                    trigger = trigger.name
                )
                if (record.iceRestartIssued || record.mediaActionDisposition == MediaActionDisposition.ACTIVE) {
                    notifyChanged(record.key.sessionId)
                    return
                }
            }
            onLog(
                "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=$trigger " +
                    "decision=WAIT_FOR_INBOUND approved=true"
            )
            return
        }
        onLog(
            "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} trigger=$trigger decision=NO_ACTION approved=true"
        )
    }

    /**
     * FAILED is not terminal while obligation OPEN (ADR-0022).
     * CHECKING / discovery are early resurrection signals 鈥?CONNECTED is not required.
     */
    private fun hasResurrectionEvidence(
        snapshot: EdgeReachabilitySnapshot,
        trigger: RecoveryReevaluateTrigger
    ): Boolean {
        if (!snapshot.linkReady || !snapshot.peerDiscovered) return false
        return when (trigger) {
            RecoveryReevaluateTrigger.ICE_CHECKING,
            RecoveryReevaluateTrigger.PEER_DISCOVERED,
            RecoveryReevaluateTrigger.PEER_REACHABILITY_RESTORED,
            RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED -> true
            RecoveryReevaluateTrigger.ROUTE_CONVERGED -> snapshot.mediaRouteConnected
            RecoveryReevaluateTrigger.AUTHORITY_REACHABLE -> snapshot.authorityReachable
            RecoveryReevaluateTrigger.ICE_RESTORED -> snapshot.linkReady && snapshot.peerDiscovered
            else -> false
        }
    }

    /**
     * R28-M: FAILED + obligation OPEN 鈥?route ICE restoration through continuation re-evaluate,
     * not direct supersede / beginRecovery.
     */
    private fun reEvaluateContinuationAfterTerminal(record: EdgeRecoveryRecord) {
        val key = record.key
        val trigger = RecoveryReevaluateTrigger.ICE_RESTORED
        // ADR-0032 搂 9: ICE may only populate the media plane. Reached only after
        // mediaRestored, so the non-media planes are known-good for this continuation.
        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = isIceConnected(key.sessionId, key.remoteModuleId),
            authorityReachable = false
        )
        val signature = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = record.initiatesReattach,
            controlPlaneStarted = record.controlPlaneStarted()
        )
        runCompletionEvaluationStub(record, snapshot, signature, trigger)
    }

    private fun admitTerminalReevaluate(
        record: EdgeRecoveryRecord,
        trigger: RecoveryReevaluateTrigger
    ): Boolean {
        if (!record.phase.isFailedMediaRecovery()) return true
        val edgeKey = record.key
        val dedupKey = TerminalReevaluateKey(record.recoveryAttemptId, trigger)
        val prior = terminalReevaluateDedup.putIfAbsent(edgeKey, dedupKey)
        if (prior == null) return true
        if (prior == dedupKey) return false
        terminalReevaluateDedup[edgeKey] = dedupKey
        return true
    }

    private fun applyReattachDispatchOutcome(
        record: EdgeRecoveryRecord,
        outcome: ReattachDispatchOutcome,
        trigger: RecoveryReevaluateTrigger? = null
    ) {
        val key = record.key
        val triggerPart = trigger?.let { " trigger=$it" } ?: ""
        when (outcome) {
            ReattachDispatchOutcome.SENT -> {
                cancelDebounce(key)
                record.phase = EdgeRecoveryPhase.REATTACH_REQUESTED
                record.reattachDeliveryState = ReattachDeliveryState.TRANSPORT_SENT
                record.outboundDispatchAttemptId = record.recoveryAttemptId
                record.outboundDispatchObligationGeneration = record.obligationGeneration
                record.reattachNonce = pendingTransportNonce.remove(key) ?: record.reattachNonce
                assignMediaActionOwner(record, MediaActionOwner.HOST_RESTART)
                val noncePart = record.reattachNonce?.let { " nonce=$it" } ?: ""
                onLog(
                    "RECOVERY_REATTACH_SENT session=${key.sessionId} remote=${key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId}$noncePart transportResult=SENT " +
                        "deliveryState=TRANSPORT_SENT controlPlaneStarted=${record.controlPlaneStarted()}"
                )
                onLog(
                    "RECOVERY_REATTACH_REQUESTED session=${key.sessionId} remote=${key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId} deliveryState=TRANSPORT_SENT " +
                        "controlPlaneStarted=${record.controlPlaneStarted()}"
                )
                onLog(
                    "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId}$triggerPart " +
                        "decision=DISPATCH_REATTACH approved=true outcome=TRANSPORT_SENT"
                )
            }
            ReattachDispatchOutcome.DEFERRED -> {
                // Coordinator defers only on the action gate (transport/discovery/signaling),
                // never on the media route (ADR-0032 INV-REC-010).
                record.phase = EdgeRecoveryPhase.RECOVERY_PENDING
                recordMediaActionDeferred(
                    record = record,
                    owner = MediaActionOwner.PARTICIPANT_REATTACH,
                    reason = DeferredReason.MEDIA_NOT_READY,
                    wakeupBinding = WakeupBinding(
                        sourceType = WakeupSourceType.ROUTE_CONVERGED,
                        sourceKey = edgeWakeupKey(key.sessionId, key.remoteModuleId)
                    ),
                    trigger = trigger?.name ?: "DISPATCH_REATTACH"
                )
                onLog(
                    "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId}$triggerPart " +
                        "decision=DISPATCH_REATTACH approved=true outcome=DEFERRED"
                )
            }
            ReattachDispatchOutcome.SESSION_CANCELLED -> {
                cancelEdge(key, "session_cancelled")
            }
            ReattachDispatchOutcome.PEER_UNREACHABLE,
            ReattachDispatchOutcome.SEND_FAILED -> {
                enterFailedMediaResidency(record, reason = "reattach_send_failed")
                onLog(
                    "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId}$triggerPart " +
                        "decision=DISPATCH_REATTACH approved=false"
                )
            }
        }
    }

    private fun supersedeFailedResidencyAndAdmit(
        record: EdgeRecoveryRecord,
        trigger: RecoveryReevaluateTrigger,
        snapshot: EdgeReachabilitySnapshot,
        signature: RecoveryCapabilitySignature
    ) {
        supersedeAttempt(record, trigger = trigger.name)
        admitSupersededRecoveryAttempt(record, trigger, snapshot, signature)
    }

    /**
     * Appendix C-3.1: supersede from FAILED residency must enter ownership lifecycle (C-10).
     * Mirrors [beginRecovery] admission after attempt open 鈥?without incrementing attempt again.
     */
    private fun admitSupersededRecoveryAttempt(
        record: EdgeRecoveryRecord,
        trigger: RecoveryReevaluateTrigger,
        snapshot: EdgeReachabilitySnapshot,
        signature: RecoveryCapabilitySignature
    ) {
        val key = record.key
        val recoveryReason = RecoveryReason.NETWORK_RECOVERY
        onLog(
            "RECOVERY_EDGE_STARTED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} initiatesReattach=${record.initiatesReattach} " +
                "immediate=false recoveryReason=$recoveryReason pathway=SUPERSEDE"
        )
        record.mediaActionOwner = MediaActionOwner.PENDING
        // J-X Slice-1: same EDGE_STARTED ownership gate as beginRecovery (no silent clear).
        supersedeCommittedNegotiationIntentIfPresent(
            record,
            reason = "EDGE_STARTED:SUPERSEDE:$trigger"
        )
        releaseDeferredIntentSlot(
            record = record,
            reason = "EDGE_STARTED:SUPERSEDE:$trigger",
            domain = DeferredIntentAuthority.RequestingDomain.MEDIA,
            kind = DeferredIntentAuthority.ReleaseKind.SUPERSEDE
        )
        clearDeferralFields(record)
        if (record.initiatesReattach) {
            if (
                RecoveryAction.DISPATCH_REATTACH in signature.permittedActions &&
                !record.controlPlaneStarted()
            ) {
                applyReattachDispatchOutcome(
                    record = record,
                    outcome = onRequestReattach(key.sessionId, record.channelId, key.remoteModuleId),
                    trigger = trigger
                )
            } else {
                // Action gate blocked (transport/discovery/signaling) 鈥?never the media
                // route (ADR-0032 INV-REC-010).
                recordMediaActionDeferred(
                    record = record,
                    owner = MediaActionOwner.PARTICIPANT_REATTACH,
                    reason = DeferredReason.MEDIA_NOT_READY,
                    wakeupBinding = WakeupBinding(
                        sourceType = WakeupSourceType.ROUTE_CONVERGED,
                        sourceKey = edgeWakeupKey(key.sessionId, key.remoteModuleId)
                    ),
                    trigger = "SUPERSEDE:$trigger"
                )
            }
        } else {
            resolveMediaActionOwner(
                record = record,
                recoveryReason = recoveryReason,
                immediate = false,
                trigger = "SUPERSEDE:$trigger"
            )
        }
    }

    private fun supersedeAttempt(
        record: EdgeRecoveryRecord,
        trigger: String,
        scheduleNewWatchdog: Boolean = true
    ) {
        val previousAttempt = record.recoveryAttemptId
        val previousPhase = record.phase
        val previousObligationOpen = record.obligationClosedAtMs == null &&
            record.obligationOpenedAtMs != null
        terminalReevaluateDedup.remove(record.key)
        recoveryOfferDeliveryPolicy.supersedeLineage(record, trigger)
        // Drop prior failed-residency deadline; next FAILED stamps a fresh one (R28-H.1).
        // Also cancel the superseded attempt's watchdog so it cannot emit FAILED (#79).
        cancelDeadline(record.key)
        cancelWatchdog(record.key)
        record.obligationDeadlineAtMs = null
        record.phase = EdgeRecoveryPhase.RECOVERY_PENDING
        record.recoveryAttemptId = ++attemptSeq
        record.iceRestartIssued = false
        record.restartDispatchAtMs = null
        clearMediaRestoredFact(record)
        record.epochRefreshUsed = false
        record.recoveryViaInboundReattach = false
        record.canonicalNegotiationOwnerModuleId = null
        record.reattachDeliveryState = ReattachDeliveryState.QUEUED
        record.reattachNonce = null
        record.outboundDispatchAttemptId = null
        record.outboundDispatchObligationGeneration = null
        record.attemptContext = null
        record.controlReconciliationFact = null
        record.recoveryStartedAtMs = clock()
        expireDeferredIceRestartIntent(record, "SUPERSEDE:$trigger")
        record.mediaActionOwner = MediaActionOwner.PENDING
        clearDeferralFields(record)
        onLog(
            formatRecoveryAttemptOpenedLog(
                sessionId = record.key.sessionId,
                remoteModuleId = record.key.remoteModuleId,
                attemptId = record.recoveryAttemptId,
                initiator = resolveRecoveryInitiator(record.initiatesReattach),
                policy = resolveRecoveryPolicy(record.initiatesReattach),
                startedAt = record.recoveryStartedAtMs,
                supersededFromAttempt = previousAttempt,
                reason = trigger,
                previousAttempt = previousAttempt,
                previousPhase = previousPhase,
                obligationOpen = previousObligationOpen,
                obligationGeneration = record.obligationGeneration,
                pathway = "SUPERSEDE"
            )
        )
        onLog(
            "RECOVERY_ATTEMPT_SUPERSEDED session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} oldAttempt=$previousAttempt " +
                "newAttempt=${record.recoveryAttemptId} reason=$trigger " +
                "supersededByModule=${if (trigger == "REATTACH_INBOUND") record.key.remoteModuleId else "NONE"} " +
                "parentAttempt=$previousAttempt"
        )
        if (scheduleNewWatchdog) {
            scheduleWatchdog(record)
        }
        notifyAttemptLineageObservation(record, "attempt_superseded", previousAttempt)
    }

    /**
     * PR5-2c-C E.14.14: debug-only deferred negotiation intent (authority edge).
     * Does not synthesize production recovery triggers.
     */
    fun debugCreateDeferredNegotiationIntent(
        sessionId: String,
        channelId: String,
        remoteModuleId: String,
        admissionSeq: Long,
        gateBlock: IceRestartGateBlockReason = IceRestartGateBlockReason.OFFER_AWAITING_ANSWER
    ): String? {
        onLog(
            "DEBUG_CREATE_DEFERRED_INTENT session=$sessionId remote=$remoteModuleId " +
                "gateBlock=$gateBlock mode=ICE_RESTART"
        )
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key]?.takeIf { it.edgeObligationOpen() }
            ?: openNewRecoveryObligation(
                key = key,
                channelId = channelId,
                phase = EdgeRecoveryPhase.RECOVERY_PENDING,
                initiatesReattach = false,
                trigger = "DEBUG_CREATE_DEFERRED_INTENT"
            )
        val intentId = allocateIceRestartIntentId(record)
        record.deferredGateBlockReason = gateBlock
        recordMediaActionDeferred(
            record = record,
            owner = MediaActionOwner.HOST_RESTART,
            reason = DeferredReason.NEGOTIATION_SETTLING,
            wakeupBinding = WakeupBinding(
                sourceType = WakeupSourceType.NEGOTIATION_CAN_EXECUTE,
                sourceKey = edgeWakeupKey(sessionId, remoteModuleId)
            ),
            trigger = "DEBUG_CREATE_DEFERRED_INTENT"
        )
        record.deferAdmissionObservationSeq = admissionSeq
        onLog(
            "DEFERRED_INTENT_CREATED session=$sessionId remote=$remoteModuleId " +
                "intentId=$intentId attemptId=${record.recoveryAttemptId} " +
                "obligationGen=${record.obligationGeneration} " +
                "baselineCapability=false admissionSeq=$admissionSeq " +
                "gateBlock=$gateBlock"
        )
        Pr52cDebugInjection.armValidationFence(sessionId, remoteModuleId, intentId)
        onLog(
            "DEFERRED_INTENT_VALIDATION_FENCE_ARMED session=$sessionId remote=$remoteModuleId " +
                "intentId=$intentId suppressProductionDrain=true"
        )
        deferredIntentAuthority.registerCreated(
            intentId = intentId,
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            fenceArmed = true
        )
        return intentId
    }

    /**
     * PR5-2c-C E.14.14 / harness: release dispatch via dispatch-readiness seam
     * (not negotiation wakeup).
     *
     * Readiness-aware: when there is no HELD(DISPATCH) deferred intent, emit
     * [DEBUG_RELEASE_NOOP] and return without calling [isRecoveryDispatchReady] /
     * drain. Field evidence (2026-08-03): unconditional readiness probe nested
     * into Coordinator sync and deadlocked the single-thread executor.
     */
    fun debugReleaseDispatchReadiness(sessionId: String, remoteModuleId: String): Boolean {
        onLog(
            "DEBUG_RELEASE_DISPATCH session=$sessionId remote=$remoteModuleId " +
                "seam=${Pr52cDebugInjection.DEBUG_RELEASE_SEAM}"
        )
        Pr52cDebugInjection.releaseDispatch(sessionId, remoteModuleId)
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key]
        val heldDispatch =
            record != null &&
                isDeferredIceRestartIntent(record) &&
                record.deferredIntentHoldReason == DeferredIntentHoldReason.DISPATCH
        if (!heldDispatch) {
            onLog(
                "DEBUG_RELEASE_NOOP session=$sessionId remote=$remoteModuleId " +
                    "seam=${Pr52cDebugInjection.DEBUG_RELEASE_SEAM} " +
                    "reason=no_held_dispatch_intent " +
                    "hold=${record?.deferredIntentHoldReason ?: "NONE"} " +
                    "intentId=${record?.iceRestartIntentId ?: "NONE"}"
            )
            return true
        }
        val dispatchReady = isRecoveryDispatchReady(sessionId, remoteModuleId)
        onLog(
            "DEBUG_RELEASE_DISPATCH_READINESS_OBSERVED session=$sessionId remote=$remoteModuleId " +
                "dispatchReady=$dispatchReady seam=${Pr52cDebugInjection.DEBUG_RELEASE_SEAM}"
        )
        retryHeldDeferredIntentDrain(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            seamTrigger = Pr52cDebugInjection.DEBUG_RELEASE_SEAM
        )
        return true
    }

    /**
     * ADR-0022 搂E.16.2 Phase-3A FA-3 Option A: explicit supersede stimulus.
     * Requires HELD(DISPATCH); does not expand DeferredIntentAuthority scope.
     */
    fun debugExplicitSupersedeDeferredIntent(
        sessionId: String,
        remoteModuleId: String
    ): Boolean {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key]
        if (record == null) {
            onLog(
                "DEBUG_EXPLICIT_SUPERSEDE_REJECT session=$sessionId remote=$remoteModuleId " +
                    "reason=no_edge stimulus=DEBUG_EXPLICIT_SUPERSEDE"
            )
            return false
        }
        val intentId = record.iceRestartIntentId
        if (intentId == null) {
            onLog(
                "DEBUG_EXPLICIT_SUPERSEDE_REJECT session=$sessionId remote=$remoteModuleId " +
                    "reason=no_intent stimulus=DEBUG_EXPLICIT_SUPERSEDE"
            )
            return false
        }
        if (deferredIntentAuthority.executionState(intentId) == null) {
            deferredIntentAuthority.registerCreated(
                intentId = intentId,
                sessionId = sessionId,
                remoteModuleId = remoteModuleId,
                fenceArmed = true
            )
        }
        if (record.deferredIntentHoldReason == DeferredIntentHoldReason.DISPATCH) {
            deferredIntentAuthority.markHeldDispatch(intentId)
        }
        val state = deferredIntentAuthority.executionState(intentId)
        if (state != DeferredIntentAuthority.ExecutionState.HELD_DISPATCH) {
            onLog(
                "DEBUG_EXPLICIT_SUPERSEDE_REJECT session=$sessionId remote=$remoteModuleId " +
                    "intentId=$intentId reason=not_held_dispatch state=${state ?: "NONE"} " +
                    "stimulus=DEBUG_EXPLICIT_SUPERSEDE"
            )
            return false
        }
        onLog(
            "DEBUG_EXPLICIT_SUPERSEDE session=$sessionId remote=$remoteModuleId " +
                "intentId=$intentId oldState=HELD_DISPATCH " +
                "stimulus=DEBUG_EXPLICIT_SUPERSEDE EXERCISE_MODE=OWNERSHIP_ISOLATION"
        )
        val result = deferredIntentAuthority.requestSupersede(
            intentId = intentId,
            reason = "DEBUG_EXPLICIT_SUPERSEDE",
            requestingDomain = DeferredIntentAuthority.RequestingDomain.TEST
        )
        if (result !is DeferredIntentAuthority.SupersedeResult.Accepted) {
            onLog(
                "DEBUG_EXPLICIT_SUPERSEDE_REJECT session=$sessionId remote=$remoteModuleId " +
                    "intentId=$intentId reason=authority_rejected stimulus=DEBUG_EXPLICIT_SUPERSEDE"
            )
            return false
        }
        // Same post-supersede slot release as EDGE_STARTED 鈥?ownership fact already emitted.
        releaseDeferredIntentSlot(
            record = record,
            reason = "DEBUG_EXPLICIT_SUPERSEDE",
            domain = DeferredIntentAuthority.RequestingDomain.TEST,
            kind = DeferredIntentAuthority.ReleaseKind.SUPERSEDE
        )
        clearDeferralFields(record)
        return true
    }
}
