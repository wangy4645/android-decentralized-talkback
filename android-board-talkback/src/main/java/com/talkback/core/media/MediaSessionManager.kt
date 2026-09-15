package com.talkback.core.media

import com.talkback.core.qos.IceConnectivity
import com.talkback.core.webrtc.FactoryHit
import com.talkback.core.webrtc.MediaBearerScope
import com.talkback.core.webrtc.ModuleMediaEngineFactory
import com.talkback.core.webrtc.WebRtcAudioEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * RO-M2a: session-bound mesh media lifecycle. One PeerConnection generation per scope transition.
 * GROUP → MEETING must not reuse a live GROUP PeerConnection (ADR-0017 / KPI MEDIA_SESSION_REUSE=0).
 * CONFERENCE → CONFERENCE reuses a live PC for CONNECTED/CHECKING/DISCONNECTED/FAILED (ADR-0018).
 */
class MediaSessionManager(
    private val factory: ModuleMediaEngineFactory,
    private val onScopedMeshIce: ((MediaBearerScope, String, String) -> Unit)? = null,
    private val closedWaitTimeoutMs: Long = 3_000L,
    private val pollIntervalMs: Long = 10L,
    private val sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val engineOwnershipGate: EngineOwnershipGate = EngineOwnershipGate(),
    private val releaseWatchdogCompleteMs: Long = 8_000L,
    private val releaseWatchdogScheduler: ReleaseWatchdogScheduler = DEFAULT_RELEASE_WATCHDOG_SCHEDULER
) {
    private data class Entry(
        val moduleId: String,
        val engine: WebRtcAudioEngine,
        val scope: MediaBearerScope,
        var lifecycle: MediaLifecycle,
        var iceState: String,
        val generation: Long
    )

    private data class PendingEngineRequest(
        val scope: MediaBearerScope,
        val origin: String,
        val sessionId: String?,
        val intent: EngineRequestIntent,
        val onReady: (WebRtcAudioEngine) -> Unit,
        val onFailed: (() -> Unit)? = null
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val generationByModule = ConcurrentHashMap<String, Long>()
    private val pendingClosed = ConcurrentHashMap.newKeySet<String>()
    private val releaseInFlight = ConcurrentHashMap.newKeySet<String>()
    private val releaseOriginByModule = ConcurrentHashMap<String, String>()
    private val pendingEngineRequests = ConcurrentHashMap<String, MutableList<PendingEngineRequest>>()
    private val pendingReleaseCompletions = ConcurrentHashMap<String, MutableList<() -> Unit>>()
    private val releaseTransactions = ConcurrentHashMap<String, ReleaseTransactionLatch>()
    private val releaseWatchdogGeneration = ConcurrentHashMap<String, Long>()
    @Volatile
    private var asyncRelease: MeshMediaAsyncRelease? = null
    @Volatile
    private var coordinatorDeferral: MeshMediaCoordinatorDeferral? = null
    @Volatile
    private var activeHangupBarrier: HangupMediaBarrier? = null
    @Volatile
    private var createContinuationEligibility: CreateContinuationEligibility? = null
    @Volatile
    private var reuseViolations = 0

    fun installCreateContinuationEligibility(eligibility: CreateContinuationEligibility) {
        createContinuationEligibility = eligibility
    }

    fun installAsyncMeshMediaRelease(release: MeshMediaAsyncRelease) {
        asyncRelease = release
    }

    fun installMeshMediaCoordinatorDeferral(deferral: MeshMediaCoordinatorDeferral) {
        coordinatorDeferral = deferral
    }

    fun registerHangupMediaBarrier(moduleIds: Collection<String>, onComplete: (allSucceeded: Boolean) -> Unit) {
        if (moduleIds.isEmpty()) {
            val complete = { onComplete(true) }
            val deferral = coordinatorDeferral
            if (deferral != null) {
                deferral.afterCurrentCoordinatorTurn(complete)
            } else {
                complete()
            }
            return
        }
        activeHangupBarrier = HangupMediaBarrier(moduleIds, onComplete)
    }

    fun create(moduleId: String, scope: MediaBearerScope): WebRtcAudioEngine {
        var ready: WebRtcAudioEngine? = null
        requestEngine(moduleId, scope, sessionId = null, onReady = { engine -> ready = engine })
        return ready ?: error("Synchronous mesh engine create pending async release for $moduleId")
    }

    fun requestEngine(
        moduleId: String,
        scope: MediaBearerScope,
        sessionId: String?,
        onReady: (WebRtcAudioEngine) -> Unit,
        onFailed: (() -> Unit)? = null,
        intent: EngineRequestIntent = EngineRequestIntent.DEFAULT,
    ) {
        val existing = entries[moduleId]
        if (existing != null && shouldReuseConferenceSession(existing, scope)) {
            onReady(existing.engine)
            return
        }
        if (existing != null && !IceConnectivity.isClosed(existing.iceState)) {
            reuseViolations++
            MediaObservabilityLog.mediaSessionReuse(
                moduleId = moduleId,
                previousScope = existing.scope,
                requestedScope = scope,
                generation = existing.generation
            )
            val conferenceEntry = existing.scope == MediaBearerScope.CONFERENCE
            if (!engineOwnershipGate.mayGroupMutateEngine(moduleId, conferenceEntry)) {
                queuePendingEngineRequest(moduleId, scope, sessionId, intent, onReady, "mediaSessionReuse", onFailed)
                logOwnershipDeferred(moduleId, "mediaSessionReuse")
                engineOwnershipGate.deferGroupAction(moduleId) {
                    entries.remove(moduleId)
                    startGroupReuseRelease(moduleId, sessionId, scope, onReady)
                }
                return
            }
            entries.remove(moduleId)
            if (asyncRelease != null) {
                queuePendingEngineRequest(moduleId, scope, sessionId, intent, onReady, "mediaSessionReuse", onFailed)
                startGroupReuseRelease(moduleId, sessionId, scope, onReady)
                return
            }
            closeSync(moduleId, existing)
            awaitClosed(setOf(moduleId))
            factory.release(moduleId)
            deliverProvisionedEngine(moduleId, scope, "mediaSessionReuse", onReady)
            return
        }
        if (releaseInFlight.contains(moduleId)) {
            queuePendingEngineRequest(moduleId, scope, sessionId, intent, onReady, "releaseInFlight", onFailed)
            return
        }
        if (scope == MediaBearerScope.GROUP &&
            !engineOwnershipGate.mayGroupMutateEngine(moduleId, hasActiveConferenceEntry = false)
        ) {
            queuePendingEngineRequest(moduleId, scope, sessionId, intent, onReady, "ownershipBarrier", onFailed)
            logOwnershipDeferred(moduleId, "requestEngine")
            engineOwnershipGate.deferGroupAction(moduleId) {
                if (asyncRelease != null) {
                    beginAsyncFactoryRelease(moduleId, sessionId, "mediaSessionProvision") { }
                } else {
                    factory.release(moduleId)
                    deliverProvisionedEngine(moduleId, scope, "requestEngine", onReady)
                }
            }
            return
        }
        if (asyncRelease != null) {
            queuePendingEngineRequest(moduleId, scope, sessionId, intent, onReady, "requestEngine", onFailed)
            beginAsyncFactoryRelease(moduleId, sessionId, "mediaSessionProvision") { }
            return
        }
        factory.release(moduleId)
        deliverProvisionedEngine(moduleId, scope, "requestEngine", onReady)
    }

    fun close(moduleId: String, sessionId: String? = null) {
        val entry = entries.remove(moduleId)
        if (entry?.scope == MediaBearerScope.CONFERENCE) {
            engineOwnershipGate.beginConferenceRelease(moduleId)
        }
        if (asyncRelease != null) {
            beginAsyncFactoryRelease(moduleId, sessionId, "mediaSessionClose") {
                entry?.let {
                    logLifecycle(it.copy(iceState = "CLOSED", lifecycle = MediaLifecycle.IDLE))
                }
            }
            return
        }
        closeSync(moduleId, entry)
    }

    /**
     * A0.4 Release Admission: submit conference release transaction immediately on coordinator.
     */
    fun admitConferenceRelease(moduleId: String, sessionId: String?, origin: String) {
        MediaObservabilityLog.mediaReleaseRequested(moduleId, origin)
        val entry = entries[moduleId]
        if (entry?.scope == MediaBearerScope.CONFERENCE) {
            entries.remove(moduleId)
            engineOwnershipGate.beginConferenceRelease(moduleId)
        } else if (entry != null) {
            entries.remove(moduleId)
            engineOwnershipGate.beginConferenceRelease(moduleId)
        } else {
            engineOwnershipGate.beginConferenceRelease(moduleId)
        }
        if (asyncRelease != null) {
            beginAsyncFactoryRelease(moduleId, sessionId, "mediaSessionClose") { }
            return
        }
        closeSync(moduleId, entry)
    }

    fun resetAll(moduleIds: Collection<String>, channelId: String? = null): MediaBarrierResult {
        val targets = moduleIds.toSet()
        targets.forEach { close(it) }
        val unresolved = if (asyncRelease != null) {
            targets.filter { releaseInFlight.contains(it) || pendingClosed.contains(it) }
        } else {
            awaitClosed(targets)
        }
        MediaObservabilityLog.mediaBarrierComplete(
            moduleCount = targets.size,
            unresolvedCount = unresolved.size,
            channelId = channelId
        )
        return MediaBarrierResult(targets.size, unresolved)
    }

    fun getState(moduleId: String): MediaSessionState? {
        val entry = entries[moduleId] ?: return null
        return MediaSessionState(
            moduleId = moduleId,
            scope = entry.scope,
            lifecycle = entry.lifecycle,
            iceState = entry.iceState,
            generation = entry.generation
        )
    }

    fun getEngine(moduleId: String): WebRtcAudioEngine? = entries[moduleId]?.engine

    fun abortPendingNegotiation() {
        entries.values.forEach { it.engine.abortPendingNegotiation() }
    }

    fun onIceStateChanged(moduleId: String, iceState: String) {
        val entry = entries[moduleId] ?: run {
            if (IceConnectivity.isClosed(iceState)) {
                pendingClosed.remove(moduleId)
            }
            return
        }
        entry.iceState = iceState
        entry.lifecycle = lifecycleFromIce(iceState, entry.lifecycle)
        if (IceConnectivity.isClosed(iceState)) {
            pendingClosed.remove(moduleId)
        }
        logLifecycle(entry)
        onScopedMeshIce?.invoke(entry.scope, moduleId, iceState)
    }

    fun mediaSessionReuseCount(): Int = reuseViolations

    fun activeModuleIds(): Set<String> = entries.keys.toSet()

    fun closeAll() {
        entries.keys.toList().forEach { close(it) }
        if (asyncRelease == null) {
            factory.releaseAll()
        }
    }

    internal fun pendingClosedModules(): Set<String> = pendingClosed.toSet()

    internal fun releaseInFlightModules(): Set<String> = releaseInFlight.toSet()

    internal fun engineOwnershipGateForTest(): EngineOwnershipGate = engineOwnershipGate

    private fun startGroupReuseRelease(
        moduleId: String,
        sessionId: String?,
        scope: MediaBearerScope,
        onReady: (WebRtcAudioEngine) -> Unit
    ) {
        beginAsyncFactoryRelease(moduleId, sessionId, "mediaSessionReuse") { }
    }

    private fun closeSync(moduleId: String, entry: Entry?) {
        pendingClosed.add(moduleId)
        factory.release(moduleId)
        if (entry == null || IceConnectivity.isClosed(entry.engine.iceConnectionState())) {
            pendingClosed.remove(moduleId)
        }
        entry?.let {
            logLifecycle(it.copy(iceState = "CLOSED", lifecycle = MediaLifecycle.IDLE))
        }
    }

    private fun beginAsyncFactoryRelease(
        moduleId: String,
        sessionId: String?,
        origin: String,
        onComplete: () -> Unit
    ) {
        if (origin == "mediaSessionReuse" &&
            !engineOwnershipGate.mayGroupMutateEngine(moduleId, hasActiveConferenceEntry = false)
        ) {
            logOwnershipDeferred(moduleId, origin)
            engineOwnershipGate.deferGroupAction(moduleId) {
                beginAsyncFactoryRelease(moduleId, sessionId, origin, onComplete)
            }
            return
        }
        val release = asyncRelease ?: run {
            if (origin == "mediaSessionClose") {
                engineOwnershipGate.beginConferenceRelease(moduleId)
            }
            factory.release(moduleId)
            completeReleaseTerminal(moduleId, origin, success = true)
            onComplete()
            return
        }
        if (!releaseInFlight.add(moduleId)) {
            queueReleaseCompletion(moduleId, onComplete)
            return
        }
        releaseOriginByModule[moduleId] = origin
        pendingClosed.add(moduleId)
        MediaObservabilityLog.mediaReleaseScheduled(moduleId, origin)
        if (origin == "mediaSessionClose") {
            beginConferenceReleaseTransaction(moduleId, origin)
        }
        release.release(
            moduleId = moduleId,
            sessionId = sessionId,
            origin = origin,
            releaseAction = { factory.release(moduleId) },
            onReleased = { success ->
                if (success) {
                    handleAsyncReleaseSuccess(moduleId, origin, onComplete)
                } else {
                    handleAsyncReleaseFailed(moduleId, origin, onComplete)
                }
            }
        )
    }

    private fun beginConferenceReleaseTransaction(moduleId: String, origin: String) {
        releaseTransactions[moduleId] = ReleaseTransactionLatch()
        val generation = (releaseWatchdogGeneration[moduleId] ?: 0L) + 1L
        releaseWatchdogGeneration[moduleId] = generation
        releaseWatchdogScheduler.schedule(releaseWatchdogCompleteMs) {
            runOnCoordinatorTurn {
                maybePreemptHungRelease(moduleId, origin, generation)
            }
        }
    }

    private fun maybePreemptHungRelease(moduleId: String, origin: String, generation: Long) {
        if (releaseWatchdogGeneration[moduleId] != generation) return
        val latch = releaseTransactions[moduleId] ?: return
        if (!latch.isRunning()) return
        if (!releaseInFlight.contains(moduleId)) return
        preemptHungRelease(moduleId, origin, generation)
    }

    private fun preemptHungRelease(moduleId: String, origin: String, generation: Long) {
        if (releaseWatchdogGeneration[moduleId] != generation) return
        val latch = releaseTransactions[moduleId] ?: return
        if (!latch.markFailedIfRunning()) return
        invalidateReleaseWatchdog(moduleId)
        MediaObservabilityLog.mediaReleaseFailed(
            moduleId,
            origin,
            ReleaseWatchdogContracts.CAUSE_HUNG_RUNTIME
        )
        clearReleaseRuntimeState(moduleId)
        completeReleaseTerminal(moduleId, origin, success = false)
        pendingReleaseCompletions.remove(moduleId)?.forEach { it() }
    }

    private fun handleAsyncReleaseSuccess(moduleId: String, origin: String, onComplete: () -> Unit) {
        if (origin == "mediaSessionClose") {
            val latch = releaseTransactions[moduleId]
            if (latch != null) {
                if (latch.isFailed()) {
                    MediaObservabilityLog.mediaReleaseLateCompletion(moduleId, origin)
                    clearReleaseRuntimeState(moduleId)
                    onComplete()
                    pendingReleaseCompletions.remove(moduleId)?.forEach { it() }
                    invalidateReleaseWatchdog(moduleId)
                    releaseTransactions.remove(moduleId)
                    return
                }
                if (!latch.markReleasedIfRunning()) {
                    clearReleaseRuntimeState(moduleId)
                    onComplete()
                    return
                }
                invalidateReleaseWatchdog(moduleId)
                releaseTransactions.remove(moduleId)
            }
        }
        MediaObservabilityLog.mediaReleased(moduleId, origin)
        completeAsyncFactoryRelease(moduleId, origin, onComplete)
    }

    private fun handleAsyncReleaseFailed(moduleId: String, origin: String, onComplete: () -> Unit) {
        if (origin == "mediaSessionClose") {
            releaseTransactions[moduleId]?.markFailedIfRunning()
            invalidateReleaseWatchdog(moduleId)
            releaseTransactions.remove(moduleId)
        }
        MediaObservabilityLog.mediaReleaseFailed(moduleId, origin)
        completeAsyncFactoryReleaseFailed(moduleId, origin, onComplete)
    }

    private fun clearReleaseRuntimeState(moduleId: String) {
        releaseInFlight.remove(moduleId)
        releaseOriginByModule.remove(moduleId)
        pendingClosed.remove(moduleId)
    }

    private fun invalidateReleaseWatchdog(moduleId: String) {
        releaseWatchdogGeneration.computeIfPresent(moduleId) { _, generation -> generation + 1L }
    }

    private fun runOnCoordinatorTurn(block: () -> Unit) {
        val deferral = coordinatorDeferral
        if (deferral != null) {
            deferral.afterCurrentCoordinatorTurn(block)
        } else {
            block()
        }
    }

    private fun queueReleaseCompletion(moduleId: String, onComplete: () -> Unit) {
        pendingReleaseCompletions.computeIfAbsent(moduleId) { mutableListOf() }.add(onComplete)
    }

    private fun completeAsyncFactoryRelease(moduleId: String, origin: String, onComplete: () -> Unit) {
        releaseInFlight.remove(moduleId)
        releaseOriginByModule.remove(moduleId)
        pendingClosed.remove(moduleId)
        completeReleaseTerminal(moduleId, origin, success = true)
        onComplete()
        val completions = pendingReleaseCompletions.remove(moduleId).orEmpty()
        completions.forEach { it() }
        if (origin != "mediaSessionClose") {
            scheduleDeferredProvision(moduleId)
        } else {
            scheduleGroupReleaseInFlightPendingDrainAfterClose(moduleId)
            scheduleCreateContinuationDrainAfterClose(moduleId)
        }
    }

    private fun completeAsyncFactoryReleaseFailed(moduleId: String, origin: String, onComplete: () -> Unit) {
        releaseInFlight.remove(moduleId)
        releaseOriginByModule.remove(moduleId)
        pendingClosed.remove(moduleId)
        completeReleaseTerminal(moduleId, origin, success = false)
        onComplete()
        pendingReleaseCompletions.remove(moduleId)?.forEach { it() }
        val pending = pendingEngineRequests.remove(moduleId).orEmpty()
        pending.forEach { request ->
            MediaObservabilityLog.mediaProvisionFailed(
                moduleId,
                request.scope,
                request.origin
            )
            request.onFailed?.invoke()
        }
    }

    private fun completeReleaseTerminal(moduleId: String, origin: String, success: Boolean) {
        if (origin == "mediaSessionClose") {
            engineOwnershipGate.completeConferenceRelease(moduleId, success)
            activeHangupBarrier?.onModuleTerminal(moduleId, success)
        }
    }

    private fun scheduleDeferredProvision(moduleId: String) {
        if (!engineOwnershipGate.mayProvisionAfterRelease(moduleId)) return
        val pending = pendingEngineRequests.remove(moduleId).orEmpty()
        if (pending.isEmpty()) return
        MediaObservabilityLog.mediaPendingDrain(
            moduleId,
            pending.map { it.scope to it.origin }
        )
        engineOwnershipGate.consumeReleaseBarrier(moduleId)
        runDeferredProvision(moduleId, pending)
    }

    /**
     * After [mediaSessionClose], drain only GROUP requests queued while release was in flight.
     * Preserves hangup stacking for CONFERENCE pending (see m03-host-pc-generation RCA).
     */
    private fun scheduleGroupReleaseInFlightPendingDrainAfterClose(moduleId: String) {
        if (!engineOwnershipGate.mayProvisionAfterRelease(moduleId)) return
        val pending = pendingEngineRequests[moduleId] ?: return
        val drainable =
            pending.filter {
                it.origin == "releaseInFlight" && it.scope == MediaBearerScope.GROUP
            }
        if (drainable.isEmpty()) return
        pending.removeAll { it.origin == "releaseInFlight" && it.scope == MediaBearerScope.GROUP }
        if (pending.isEmpty()) {
            pendingEngineRequests.remove(moduleId)
        }
        MediaObservabilityLog.mediaPendingDrain(
            moduleId,
            drainable.map { it.scope to it.origin }
        )
        engineOwnershipGate.consumeReleaseBarrier(moduleId)
        runDeferredProvision(moduleId, drainable)
    }

    private fun runDeferredProvision(
        moduleId: String,
        pending: List<PendingEngineRequest>,
    ) {
        val provision = {
            pending.forEach { request ->
                MediaObservabilityLog.mediaProvisionRequested(
                    moduleId,
                    request.scope,
                    request.origin
                )
                deliverProvisionedEngine(moduleId, request.scope, request.origin, request.onReady)
            }
        }
        val deferral = coordinatorDeferral
        if (deferral != null) {
            deferral.afterCurrentCoordinatorTurn(provision)
        } else {
            provision()
        }
    }

    private fun runCreateContinuationProvision(
        moduleId: String,
        pending: List<PendingEngineRequest>,
    ) {
        val provision = {
            pending.forEach { request ->
                MediaObservabilityLog.mediaProvisionRequested(
                    moduleId,
                    request.scope,
                    request.origin
                )
                deliverProvisionedEngine(moduleId, request.scope, request.origin, request.onReady)
                MediaObservabilityLog.mediaCreateContinuationResumed(moduleId, request.sessionId)
            }
        }
        val deferral = coordinatorDeferral
        if (deferral != null) {
            deferral.afterCurrentCoordinatorTurn(provision)
        } else {
            provision()
        }
    }

    /**
     * ADR-0061: after [mediaSessionClose], drain CREATE_CONTINUATION only.
     * Fence → consume pending → RESUMED or DISCARDED → exactly one terminal callback.
     */
    private fun scheduleCreateContinuationDrainAfterClose(moduleId: String) {
        if (!engineOwnershipGate.mayProvisionAfterRelease(moduleId)) return
        val pendingList = pendingEngineRequests[moduleId] ?: return
        val candidates = pendingList.filter {
            it.scope == MediaBearerScope.CONFERENCE &&
                it.intent == EngineRequestIntent.CREATE_CONTINUATION &&
                it.origin == "releaseInFlight"
        }
        if (candidates.isEmpty()) return

        val eligibility = createContinuationEligibility
        val toResume = mutableListOf<PendingEngineRequest>()
        val toDiscard = mutableListOf<Pair<PendingEngineRequest, String>>()

        candidates.forEach { request ->
            val sessionId = request.sessionId
            when {
                sessionId == null ->
                    toDiscard.add(request to "session_absent")
                eligibility == null ->
                    toDiscard.add(request to "eligibility_unconfigured")
                !eligibility.isEligible(sessionId, moduleId) ->
                    toDiscard.add(request to "fence_rejected")
                else ->
                    toResume.add(request)
            }
        }

        // Consume before any callback — prevents re-entrancy breaking T5 exactly-once.
        pendingList.removeAll(candidates.toSet())
        if (pendingList.isEmpty()) {
            pendingEngineRequests.remove(moduleId)
        }

        if (toResume.isNotEmpty()) {
            val encoded = toResume.mapIndexed { index, item ->
                "$index:${item.scope}:${item.origin}:${item.intent}"
            }.joinToString(",")
            MediaObservabilityLog.mediaCreateContinuationDrain(
                moduleId,
                toResume.size,
                encoded
            )
            engineOwnershipGate.consumeReleaseBarrier(moduleId)
            runCreateContinuationProvision(moduleId, toResume)
        }

        toDiscard.forEach { (request, reason) ->
            MediaObservabilityLog.mediaCreateContinuationDiscarded(moduleId, request.sessionId, reason)
            MediaObservabilityLog.mediaProvisionFailed(
                moduleId,
                request.scope,
                request.origin
            )
            request.onFailed?.invoke()
        }
    }

    private fun queuePendingEngineRequest(
        moduleId: String,
        scope: MediaBearerScope,
        sessionId: String?,
        intent: EngineRequestIntent,
        onReady: (WebRtcAudioEngine) -> Unit,
        origin: String,
        onFailed: (() -> Unit)? = null
    ) {
        pendingEngineRequests.computeIfAbsent(moduleId) { mutableListOf() }
            .add(
                PendingEngineRequest(
                    scope = scope,
                    origin = origin,
                    sessionId = sessionId,
                    intent = intent,
                    onReady = onReady,
                    onFailed = onFailed
                )
            )
        MediaObservabilityLog.mediaReleaseDeferredCreate(moduleId, scope, intent, sessionId)
    }

    private fun deliverProvisionedEngine(
        moduleId: String,
        scope: MediaBearerScope,
        origin: String,
        onReady: (WebRtcAudioEngine) -> Unit
    ) {
        val provisioned = provisionNew(moduleId, scope)
        MediaObservabilityLog.mediaProvisioned(
            moduleId = moduleId,
            scope = scope,
            origin = origin,
            generation = provisioned.generation,
            pcHash = provisioned.engine.diagnosticPeerConnectionHash(),
            factoryHit = provisioned.factoryHit
        )
        onReady(provisioned.engine)
    }

    private data class ProvisionedEngine(
        val engine: WebRtcAudioEngine,
        val generation: Long,
        val factoryHit: FactoryHit
    )

    private fun provisionNew(moduleId: String, scope: MediaBearerScope): ProvisionedEngine {
        val generation = (generationByModule[moduleId] ?: 0L) + 1L
        generationByModule[moduleId] = generation
        val created = factory.getOrCreateDetailed(moduleId)
        val engine = created.engine
        val entry = Entry(
            moduleId = moduleId,
            engine = engine,
            scope = scope,
            lifecycle = MediaLifecycle.BOOTSTRAPPING,
            iceState = engine.iceConnectionState(),
            generation = generation
        )
        entries[moduleId] = entry
        when (scope) {
            MediaBearerScope.CONFERENCE -> engineOwnershipGate.onConferenceEngineProvisioned(moduleId)
            MediaBearerScope.GROUP -> engineOwnershipGate.onGroupEngineProvisioned(moduleId)
            MediaBearerScope.UNICAST -> Unit
        }
        logLifecycle(entry)
        return ProvisionedEngine(engine, generation, created.factoryHit)
    }

    private fun logOwnershipDeferred(moduleId: String, origin: String) {
        val snapshot = engineOwnershipGate.stateSnapshot(moduleId)
        MediaObservabilityLog.engineOwnershipDeferred(
            moduleId = moduleId,
            owner = snapshot?.first ?: EngineOwnershipGate.Owner.CONFERENCE_EDGE,
            state = snapshot?.second ?: EngineOwnershipGate.State.RELEASING,
            origin = origin
        )
    }

    private fun awaitClosed(moduleIds: Set<String>): List<String> {
        if (moduleIds.isEmpty()) return emptyList()
        val deadline = clock() + closedWaitTimeoutMs
        while (clock() < deadline) {
            val unresolved = moduleIds.filter { pendingClosed.contains(it) }
            if (unresolved.isEmpty()) return emptyList()
            sleeper(pollIntervalMs)
        }
        return moduleIds.filter { pendingClosed.contains(it) }
    }

    private fun lifecycleFromIce(iceState: String, current: MediaLifecycle): MediaLifecycle = when {
        IceConnectivity.isClosed(iceState) -> MediaLifecycle.IDLE
        iceState == "FAILED" -> MediaLifecycle.FAILED
        iceState == "DISCONNECTED" -> MediaLifecycle.DEGRADED
        IceConnectivity.isConnected(iceState) -> MediaLifecycle.CONNECTED
        iceState == "CHECKING" -> MediaLifecycle.NEGOTIATING
        iceState == "NEW" -> when (current) {
            MediaLifecycle.IDLE -> MediaLifecycle.BOOTSTRAPPING
            else -> current
        }
        else -> current
    }

    private fun shouldReuseConferenceSession(existing: Entry, requestedScope: MediaBearerScope): Boolean =
        requestedScope == MediaBearerScope.CONFERENCE &&
            existing.scope == MediaBearerScope.CONFERENCE &&
            !IceConnectivity.isClosed(existing.iceState)

    private fun logLifecycle(entry: Entry) {
        MediaObservabilityLog.mediaLifecycle(
            moduleId = entry.moduleId,
            scope = entry.scope,
            lifecycle = entry.lifecycle,
            generation = entry.generation,
            iceState = entry.iceState
        )
    }

    companion object {
        private val defaultWatchdogExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "media-release-watchdog").apply { isDaemon = true }
        }

        private val DEFAULT_RELEASE_WATCHDOG_SCHEDULER = ReleaseWatchdogScheduler { delayMs, action ->
            defaultWatchdogExecutor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
        }
    }
}
