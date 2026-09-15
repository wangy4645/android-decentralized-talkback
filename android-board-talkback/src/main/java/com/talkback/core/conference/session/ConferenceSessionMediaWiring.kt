package com.talkback.core.conference.session

import android.content.Context
import com.talkback.core.conference.authority.AuthorityWiringRuntime
import com.talkback.core.conference.authority.MediaKeyContextFact
import com.talkback.core.conference.authority.SourceAuthorizationFact
import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.transport.MulticastLockPolicy
import com.talkback.core.conference.runtime.TransportHandle
import com.talkback.core.conference.transport.ConferenceMulticastNetworkBinding
import com.talkback.core.conference.transport.ConferenceMulticastRealMediaAssembly
import com.talkback.core.conference.transport.ConferenceMulticastRtpSrtpTransport
import com.talkback.core.conference.transport.PipelineAdmitResult
import com.talkback.core.conference.transport.PipelinePlayoutResult
import com.talkback.core.conference.transport.RecordingPlayoutMetricsSeam
import com.talkback.core.conference.transport.RelativeMediaTimeline
import com.talkback.core.conference.session.integration.Profile01ShadowRuntimeObservability
import com.talkback.core.conference.session.integration.cutover.AudiblePlayoutOwnershipSeam
import com.talkback.core.conference.session.integration.cutover.audiblePlayoutSeam
import com.talkback.core.conference.wire.WireIngressResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 2 — session-owned multicast media wiring (minimal seam).
 *
 * Owns one [ConferenceMulticastRealMediaAssembly] per conference session.
 * Coordinator delegates lifecycle only — no catalog/epoch state in coordinator.
 */
class ConferenceSessionMediaWiring(
    private val assemblyFactory: (Context?, sessionId: String) -> ConferenceMulticastRealMediaAssembly,
    private val appContext: Context? = null,
) {
    private data class SessionState(
        var fact: ConferenceSessionMediaFact,
        val assembly: ConferenceMulticastRealMediaAssembly,
        val catalog: SourceBindingCatalog,
        val mediaTimeline: RelativeMediaTimeline,
        var ingressBlocked: Boolean,
        var playoutStarted: Boolean,
        var lastPlayoutTickMediaTimeMs: Long? = null,
    )

    private val sessions = linkedMapOf<String, SessionState>()
    private val sessionPipelineLocks = ConcurrentHashMap<String, Any>()

    data class BufferedMixSlot(
        val slot: Long,
        val slotMediaTimeMs: Long,
    )

    fun sessionPlayoutAnchorMs(sessionId: String): Long? {
        val state = sessions[sessionId] ?: return null
        return state.mediaTimeline.anchorWallMs() ?: state.fact.startedAtMs
    }

    fun withSessionPipelineLock(
        sessionId: String,
        block: () -> Unit,
    ) {
        val lock = sessionPipelineLocks.computeIfAbsent(sessionId) { Any() }
        synchronized(lock) {
            block()
        }
    }

    fun capturePlayoutFunnelSnapshot(
        sessionId: String,
        tickMediaTimeMs: Long,
        resolvedMixSlot: Long? = null,
    ): PlayoutFunnelSnapshot? {
        val state = sessions[sessionId] ?: return null
        if (state.ingressBlocked) return null
        val orchestrator = state.assembly.orchestrator
        val pipeline = orchestrator.pipeline
        val admitted = orchestrator.authority.store.currentAdmitted()
        val anchorMs = state.mediaTimeline.anchorWallMs() ?: state.fact.startedAtMs
        val playoutTargetSlot = state.mediaTimeline.mediaSlotForPlayoutTickMs(tickMediaTimeMs)
        val perSource =
            admitted.map { (sourceIdentity, source) ->
                pipeline.jitterSlotDomainSnapshot(sourceIdentity, source.incarnationId)
                    ?: JitterSlotDomainSnapshot(
                        sourceIdentity = sourceIdentity,
                        incarnationId = source.incarnationId,
                        nextExpectedSlot = null,
                        bySlotSize = 0,
                        earliestBufferedSlot = null,
                        latestBufferedSlot = null,
                        executable = pipeline.isJitterExecutable(sourceIdentity, source.incarnationId),
                    )
            }
        return PlayoutFunnelSnapshot(
            tickMediaTimeMs = tickMediaTimeMs,
            playoutAnchorMs = anchorMs,
            playoutTargetSlot = playoutTargetSlot,
            resolvedMixSlot = resolvedMixSlot,
            selectedTopK = orchestrator.selection.currentTopK().members.map { it.sourceIdentity },
            activeJitterSources = pipeline.activeJitterSourceCount(),
            admittedCount = admitted.size,
            perSource = perSource,
        )
    }

    /**
     * RCA5-B2 — map playout tick onto anchored RTP media-slot domain and return the
     * earliest buffered slot that is playable at or before the tick target.
     */
    fun resolvePlayoutMixSlot(
        sessionId: String,
        tickMediaTimeMs: Long,
    ): BufferedMixSlot? {
        val state = sessions[sessionId] ?: return null
        if (state.ingressBlocked) return null
        state.lastPlayoutTickMediaTimeMs = tickMediaTimeMs
        val targetMediaSlot = state.mediaTimeline.mediaSlotForPlayoutTickMs(tickMediaTimeMs)
        if (targetMediaSlot == null) {
            return resolveEarliestBufferedMixSlot(sessionId)
        }
        val pipeline = state.assembly.orchestrator.pipeline
        val admitted = state.assembly.orchestrator.authority.store.currentAdmitted()
        var bestSlot: Long? = null
        var bestMediaTimeMs: Long? = null
        for ((sourceIdentity, source) in admitted) {
            val nextExpected = pipeline.nextExpectedSlot(sourceIdentity, source.incarnationId)
            val buffered = pipeline.bufferedSlots(sourceIdentity, source.incarnationId)
            val eligible =
                buffered.filter { slot -> nextExpected == null || slot >= nextExpected }
            val slot =
                eligible.filter { it <= targetMediaSlot }.minOrNull()
                    ?: eligible.minOrNull()
                    ?: continue
            val frame =
                pipeline.peekBufferedFrame(sourceIdentity, source.incarnationId, slot)
                    ?: continue
            if (bestSlot == null || slot < bestSlot) {
                bestSlot = slot
                bestMediaTimeMs = frame.mediaTimeMs
            }
        }
        return if (bestSlot != null && bestMediaTimeMs != null) {
            BufferedMixSlot(slot = bestSlot, slotMediaTimeMs = bestMediaTimeMs)
        } else {
            null
        }
    }

    fun resolveEarliestBufferedMixSlot(sessionId: String): BufferedMixSlot? {
        val state = sessions[sessionId] ?: return null
        if (state.ingressBlocked) return null
        val pipeline = state.assembly.orchestrator.pipeline
        val admitted = state.assembly.orchestrator.authority.store.currentAdmitted()
        var bestSlot: Long? = null
        var bestMediaTimeMs: Long? = null
        for ((sourceIdentity, source) in admitted) {
            val slots = pipeline.bufferedSlots(sourceIdentity, source.incarnationId)
            val slot = slots.minOrNull() ?: continue
            val frame =
                pipeline.peekBufferedFrame(sourceIdentity, source.incarnationId, slot)
                    ?: continue
            if (bestSlot == null || slot < bestSlot) {
                bestSlot = slot
                bestMediaTimeMs = frame.mediaTimeMs
            }
        }
        return if (bestSlot != null && bestMediaTimeMs != null) {
            BufferedMixSlot(slot = bestSlot, slotMediaTimeMs = bestMediaTimeMs)
        } else {
            null
        }
    }

    fun runMixPlayoutCycle(
        sessionId: String,
        nowMs: Long,
        slot: Long,
        slotMediaTimeMs: Long,
    ): PipelinePlayoutResult? {
        val state = sessions[sessionId] ?: return null
        if (state.ingressBlocked) return null
        return state.assembly.pipeline.runMixPlayoutCycle(
            nowMs = nowMs,
            slot = slot,
            slotMediaTimeMs = slotMediaTimeMs,
        )
    }

    fun playoutSuccessfulWrites(sessionId: String): Long? {
        val state = sessions[sessionId] ?: return null
        return when (val metrics = state.assembly.playoutMetrics) {
            is AudiblePlayoutOwnershipSeam -> metrics.successfulWrites
            is RecordingPlayoutMetricsSeam -> metrics.successfulWrites
            else -> null
        }
    }

    fun audiblePlayoutSeam(sessionId: String): AudiblePlayoutOwnershipSeam? =
        sessions[sessionId]?.assembly?.audiblePlayoutSeam()

    fun currentMediaKeyEpoch(sessionId: String): Long? = sessions[sessionId]?.fact?.mediaKeyEpoch

    /** Harness-only — exposes authority runtime for cross-node crypto regression tests. */
    internal fun authorityRuntime(sessionId: String): AuthorityWiringRuntime? =
        sessions[sessionId]?.assembly?.orchestrator?.authority

    fun startSession(fact: ConferenceSessionMediaFact): Boolean {
        val existing = sessions[fact.sessionId]
        if (existing != null) {
            return when {
                fact.mediaKeyEpoch > existing.fact.mediaKeyEpoch ->
                    rotateMediaKeyEpoch(fact.sessionId, fact)
                fact.mediaKeyEpoch == existing.fact.mediaKeyEpoch ->
                    refreshMediaKeyMaterialIfChanged(fact.sessionId, fact)
                else -> false
            }
        }
        val assembly = assemblyFactory(appContext, fact.sessionId)
        val store = assembly.orchestrator.authority.store
        store.acceptVerifiedKey(
            MediaKeyContextFact(
                mediaKeyEpoch = fact.mediaKeyEpoch,
                masterKey = fact.masterKey.copyOf(),
                masterSalt = fact.masterSalt.copyOf(),
                keyContextHint64 = fact.keyContextHint64.copyOf(),
            ),
        )
        val transport = assembly.pipeline.transport
        val handle =
            TransportHandle(
                id = "session-media-${fact.sessionId}",
                endpoint = fact.endpoint,
                networkBinding =
                    ConferenceMulticastNetworkBinding.fromInterfaceName(fact.networkInterfaceName),
                multicastLockPolicy = MulticastLockPolicy.WIFI_MULTICAST_LOCK,
            )
        if (!transport.beginScope(handle, fact.startedAtMs)) {
            return false
        }
        transport.configureNetworkInterface(fact.networkInterfaceName)
        sessions[fact.sessionId] =
            SessionState(
                fact = fact,
                assembly = assembly,
                catalog = SourceBindingCatalog(),
                mediaTimeline = RelativeMediaTimeline(),
                ingressBlocked = false,
                playoutStarted = false,
            )
        Profile01ShadowRuntimeObservability.bindActiveSession(
            sessionId = fact.sessionId,
            conferenceId = null,
            mediaKeyEpoch = fact.mediaKeyEpoch,
        )
        runtimeSnapshot(fact.sessionId)?.let { snap ->
            Profile01ShadowRuntimeObservability.logRuntimeSnapshot(
                phase = "WIRING_SESSION_STARTED",
                sessionId = fact.sessionId,
                conferenceId = null,
                mediaKeyEpoch = fact.mediaKeyEpoch,
                snapshot = snap,
            )
        }
        return true
    }

    /**
     * Membership convergence media-key epoch bump — atomic retire old epoch + install new key.
     * Caller reinstalls member bindings at the new epoch after rotation.
     */
    fun rotateMediaKeyEpoch(
        sessionId: String,
        newFact: ConferenceSessionMediaFact,
    ): Boolean {
        val lock = sessionPipelineLocks.computeIfAbsent(sessionId) { Any() }
        synchronized(lock) {
            val state = sessions[sessionId] ?: return false
            if (state.ingressBlocked) return false
            if (newFact.sessionId != sessionId) return false
            val oldEpoch = state.fact.mediaKeyEpoch
            when {
                newFact.mediaKeyEpoch < oldEpoch -> return false
                newFact.mediaKeyEpoch == oldEpoch ->
                    return refreshMediaKeyMaterialIfChanged(sessionId, newFact)
            }
            val authority = state.assembly.orchestrator.authority
            val pipeline = state.assembly.orchestrator.pipeline
            val decodeMix = state.assembly.orchestrator.decodeMix
            for (entry in state.catalog.all()) {
                authority.revokeSource(entry.sourceIdentity, entry.incarnationId)
                decodeMix.hardFence(entry.sourceIdentity, entry.incarnationId)
                pipeline.drainSourceAfterSessionRevoke(entry.sourceIdentity)
            }
            state.catalog.clear()
            authority.retireMediaKeyEpoch(oldEpoch)
            pipeline.drainAllForSessionWiring()
            authority.store.acceptVerifiedKey(
                MediaKeyContextFact(
                    mediaKeyEpoch = newFact.mediaKeyEpoch,
                    masterKey = newFact.masterKey.copyOf(),
                    masterSalt = newFact.masterSalt.copyOf(),
                    keyContextHint64 = newFact.keyContextHint64.copyOf(),
                ),
            )
            state.fact = newFact
            Profile01ShadowRuntimeObservability.bindActiveSession(
                sessionId = sessionId,
                conferenceId = Profile01ShadowRuntimeObservability.activeConferenceId,
                mediaKeyEpoch = newFact.mediaKeyEpoch,
            )
            runtimeSnapshot(sessionId)?.let { snap ->
                Profile01ShadowRuntimeObservability.logRuntimeSnapshot(
                    phase = "WIRING_MEDIA_KEY_EPOCH_ROTATED",
                    sessionId = sessionId,
                    conferenceId = Profile01ShadowRuntimeObservability.activeConferenceId,
                    mediaKeyEpoch = newFact.mediaKeyEpoch,
                    snapshot = snap,
                    extra =
                        mapOf(
                            "previousMediaKeyEpoch" to oldEpoch.toString(),
                            "generation" to newFact.generation.toString(),
                        ),
                )
            }
            return true
        }
    }

    /**
     * Propagate authoritative session key refresh at an unchanged mediaKeyEpoch
     * (e.g. supplement-backed registry correction after stale republication).
     */
    private fun refreshMediaKeyMaterialIfChanged(
        sessionId: String,
        newFact: ConferenceSessionMediaFact,
    ): Boolean {
        val lock = sessionPipelineLocks.computeIfAbsent(sessionId) { Any() }
        synchronized(lock) {
            val state = sessions[sessionId] ?: return false
            if (state.ingressBlocked) return false
            if (newFact.sessionId != sessionId) return false
            if (newFact.mediaKeyEpoch != state.fact.mediaKeyEpoch) return false
            if (sessionKeyMaterialMatches(state.fact, newFact)) return true
            val authority = state.assembly.orchestrator.authority
            authority.store.acceptVerifiedKey(
                MediaKeyContextFact(
                    mediaKeyEpoch = newFact.mediaKeyEpoch,
                    masterKey = newFact.masterKey.copyOf(),
                    masterSalt = newFact.masterSalt.copyOf(),
                    keyContextHint64 = newFact.keyContextHint64.copyOf(),
                ),
            )
            state.fact = newFact
            return true
        }
    }

    private fun sessionKeyMaterialMatches(
        left: ConferenceSessionMediaFact,
        right: ConferenceSessionMediaFact,
    ): Boolean =
        left.masterKey.contentEquals(right.masterKey) &&
            left.masterSalt.contentEquals(right.masterSalt) &&
            left.keyContextHint64.contentEquals(right.keyContextHint64)

    fun installMember(
        sessionId: String,
        binding: MemberBindingFact,
    ): Boolean {
        val state = sessions[sessionId] ?: return false
        if (state.ingressBlocked) return false
        if (binding.mediaKeyEpoch != state.fact.mediaKeyEpoch) return false
        val authority = state.assembly.orchestrator.authority
        val source =
            SourceAuthorizationFact(
                sourceIdentity = binding.sourceIdentity,
                incarnationId = binding.incarnationId,
                ssrc = binding.ssrc,
                sourceAdmissionKey48 = binding.sourceAdmissionKey48.copyOf(),
                mediaKeyEpoch = binding.mediaKeyEpoch,
            )
        authority.store.acceptVerifiedSource(source)
        authority.syncAdmittedToRuntime(binding.sourceIdentity)
        state.catalog.install(binding)
        ensurePlayoutStarted(state)
        runtimeSnapshot(sessionId)?.let { snap ->
            Profile01ShadowRuntimeObservability.logRuntimeSnapshot(
                phase = "WIRING_MEMBER_INSTALLED",
                sessionId = sessionId,
                conferenceId = Profile01ShadowRuntimeObservability.activeConferenceId,
                mediaKeyEpoch = state.fact.mediaKeyEpoch,
                snapshot = snap,
                extra = Profile01ShadowRuntimeObservability.memberBindingFields(binding),
            )
        }
        return true
    }

    fun removeMember(
        sessionId: String,
        moduleId: String,
        incarnationId: Long,
    ): Boolean {
        val state = sessions[sessionId] ?: return false
        val current = state.catalog.get(moduleId)
        if (current != null && current.incarnationId != incarnationId) {
            return false
        }
        val obsExtra =
            if (current != null) {
                Profile01ShadowRuntimeObservability.memberIdentityFields(
                    moduleId = current.moduleId,
                    incarnationId = current.incarnationId,
                    ssrc = current.ssrc,
                )
            } else {
                mapOf(
                    "moduleId" to moduleId,
                    "sourceGeneration" to incarnationId.toString(),
                    "incarnationId" to incarnationId.toString(),
                )
            }
        revokeAndDrain(state, moduleId, incarnationId)
        state.catalog.remove(moduleId)
        runtimeSnapshot(sessionId)?.let { snap ->
            Profile01ShadowRuntimeObservability.logRuntimeSnapshot(
                phase = "WIRING_MEMBER_REVOKED",
                sessionId = sessionId,
                conferenceId = Profile01ShadowRuntimeObservability.activeConferenceId,
                mediaKeyEpoch = state.fact.mediaKeyEpoch,
                snapshot = snap,
                extra = obsExtra,
            )
        }
        return true
    }

    fun replaceMember(
        sessionId: String,
        oldBinding: MemberBindingFact,
        newBinding: MemberBindingFact,
    ): Boolean {
        if (oldBinding.moduleId != newBinding.moduleId) return false
        if (oldBinding.incarnationId == newBinding.incarnationId) return false
        if (newBinding.mediaKeyEpoch != oldBinding.mediaKeyEpoch) return false
        val state = sessions[sessionId] ?: return false
        if (state.ingressBlocked) return false
        val current = state.catalog.get(oldBinding.moduleId)
        if (current == null || current.incarnationId != oldBinding.incarnationId) {
            return false
        }
        revokeAndDrain(state, oldBinding.moduleId, oldBinding.incarnationId)
        state.catalog.remove(oldBinding.moduleId)
        runtimeSnapshot(sessionId)?.let { snap ->
            Profile01ShadowRuntimeObservability.logRuntimeSnapshot(
                phase = "WIRING_MEMBER_REVOKED",
                sessionId = sessionId,
                conferenceId = Profile01ShadowRuntimeObservability.activeConferenceId,
                mediaKeyEpoch = state.fact.mediaKeyEpoch,
                snapshot = snap,
                extra = Profile01ShadowRuntimeObservability.memberBindingFields(oldBinding),
            )
        }
        return installMember(sessionId, newBinding)
    }

    /**
     * Symmetric session teardown — authority first, execution resources second.
     *
     * ```text
     * block ingress → revoke all → stop playout → retire epoch → endScope → drain runtime
     * ```
     */
    fun stopSession(
        sessionId: String,
        generation: Long,
    ): Boolean {
        val lock = sessionPipelineLocks.computeIfAbsent(sessionId) { Any() }
        synchronized(lock) {
            val state = sessions.remove(sessionId) ?: return false
            if (state.fact.generation != generation) {
                sessions[sessionId] = state
                return false
            }
            if (!state.ingressBlocked) {
                state.ingressBlocked = true
            }
            val authority = state.assembly.orchestrator.authority
            val pipeline = state.assembly.orchestrator.pipeline
            val decodeMix = state.assembly.orchestrator.decodeMix
            val entries = state.catalog.all()
            for (entry in entries) {
                authority.revokeSource(entry.sourceIdentity, entry.incarnationId)
                decodeMix.hardFence(entry.sourceIdentity, entry.incarnationId)
                pipeline.drainSourceAfterSessionRevoke(entry.sourceIdentity)
            }
            state.catalog.clear()
            state.assembly.stopPlayout()
            authority.retireMediaKeyEpoch(state.fact.mediaKeyEpoch)
            pipeline.drainAllForSessionWiring()
            drainResidualDecoders(decodeMix)
            state.assembly.orchestrator.selection.clearRegistryForSessionWiring()
            state.assembly.pipeline.transport.endScope()
            assertRuntimeEmptyAfterStop(state)
            Profile01ShadowRuntimeObservability.logTeardown(
                sessionId = sessionId,
                conferenceId = Profile01ShadowRuntimeObservability.activeConferenceId,
                mediaKeyEpoch = state.fact.mediaKeyEpoch,
                snapshot =
                    SessionMediaRuntimeSnapshot(
                        sessionId = sessionId,
                        generation = state.fact.generation,
                        ingressBlocked = true,
                        transportScopeActive = false,
                        catalogEntries = 0,
                        admittedCount = 0,
                        activeJitterSources = 0,
                        liveDecoders = 0,
                        jitterBufferCount = 0,
                    ),
            )
            Profile01ShadowRuntimeObservability.clearActiveSession(sessionId)
            sessionPipelineLocks.remove(sessionId)
            return true
        }
    }

    /** Wire-layer admit only (SourceBindingGate Q3). Does not run jitter / ingress metrics pipeline. */
    fun admitDatagram(
        sessionId: String,
        datagram: ByteArray,
        roc: Int = 0,
    ): WireIngressResult {
        val state = sessions[sessionId]
            ?: return WireIngressResult.Rejected(
                com.talkback.core.conference.wire.WireOwningSeam.Q3,
                "SESSION_NOT_ACTIVE",
                "no session media wiring",
            )
        if (state.ingressBlocked) {
            return WireIngressResult.Rejected(
                com.talkback.core.conference.wire.WireOwningSeam.Q3,
                "SESSION_INGRESS_BLOCKED",
                "session teardown in progress",
            )
        }
        val entry =
            SessionBindingIngressResolver.resolveBySsrc(datagram, state.catalog)
                ?: return WireIngressResult.Rejected(
                    com.talkback.core.conference.wire.WireOwningSeam.Q3,
                    "UNKNOWN_SSRC",
                    "no catalog binding",
                )
        return state.assembly.orchestrator.authority.admitWire(
            sourceIdentity = entry.sourceIdentity,
            datagram = datagram,
            roc = roc,
        )
    }

    /**
     * Wire admit + post-admit execution (jitter frame admit, ingress metrics, shadow OBS projection).
     * Shadow RX must use this — [admitDatagram] wire-only path is insufficient for INGRESS_ACTIVITY.
     */
    fun admitProtectedDatagram(
        sessionId: String,
        datagram: ByteArray,
        rxWallMs: Long,
        roc: Int = 0,
    ): PipelineAdmitResult {
        val state = sessions[sessionId]
            ?: return pipelineReject(
                "SESSION_NOT_ACTIVE",
                "no session media wiring",
            )
        if (state.ingressBlocked) {
            return pipelineReject(
                "SESSION_INGRESS_BLOCKED",
                "session teardown in progress",
            )
        }
        val entry =
            SessionBindingIngressResolver.resolveBySsrc(datagram, state.catalog)
                ?: return pipelineReject(
                    "UNKNOWN_SSRC",
                    "no catalog binding",
                )
        var result: PipelineAdmitResult? = null
        withSessionPipelineLock(sessionId) {
            val locked = sessions[sessionId]
            if (locked == null) {
                result =
                    pipelineReject(
                        "SESSION_NOT_ACTIVE",
                        "no session media wiring",
                    )
                return@withSessionPipelineLock
            }
            if (locked.ingressBlocked) {
                result =
                    pipelineReject(
                        "SESSION_INGRESS_BLOCKED",
                        "session teardown in progress",
                    )
                return@withSessionPipelineLock
            }
            result =
                alignIngressAfterAdmit(
                    state = locked,
                    sourceIdentity = entry.sourceIdentity,
                    result =
                        locked.assembly.pipeline.admitProtectedDatagram(
                            sourceIdentity = entry.sourceIdentity,
                            datagram = datagram,
                            rxWallMs = rxWallMs,
                            roc = roc,
                            mediaTimeline = locked.mediaTimeline,
                            playoutReferenceMs =
                                locked.lastPlayoutTickMediaTimeMs
                                    ?: locked.mediaTimeline.anchorWallMs(),
                        ),
                    nowMs = rxWallMs,
                )
        }
        return result!!
    }

    private fun alignIngressAfterAdmit(
        state: SessionState,
        sourceIdentity: String,
        result: PipelineAdmitResult,
        nowMs: Long,
    ): PipelineAdmitResult {
        if (result.frameAdmit != FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED) {
            return result
        }
        val admitted =
            state.assembly.orchestrator.authority.store.derivedAdmitted(sourceIdentity)
                ?: return result
        val aligned =
            SessionMediaLiveEdgeAligner.maybeAlignLiveEdgeOnReorder(
                pipeline = state.assembly.orchestrator.pipeline,
                sourceIdentity = sourceIdentity,
                incarnationId = admitted.incarnationId,
                liveSlot = result.mediaSlot,
                mediaTimeMs = result.mediaTimeMs ?: nowMs,
                arrivalMs = nowMs,
                nowMs = nowMs,
            ) ?: return result
        return result.copy(
            frameAdmit = aligned.disposition,
            jitterDepth =
                state.assembly.orchestrator.pipeline.jitterSize(
                    sourceIdentity,
                    admitted.incarnationId,
                ),
        )
    }

    private fun pipelineReject(
        frozenClass: String,
        reason: String,
    ): PipelineAdmitResult =
        PipelineAdmitResult(
            ingress =
                WireIngressResult.Rejected(
                    com.talkback.core.conference.wire.WireOwningSeam.Q3,
                    frozenClass,
                    reason,
                ),
            frameAdmit = null,
            jitterDepth = 0,
            mediaSlot = null,
            mediaTimeMs = null,
        )

    fun orchestrator(sessionId: String): ConferenceMediaExecutionOrchestrator? =
        sessions[sessionId]?.assembly?.orchestrator

    /** Field / directed observation only — same session-owned transport begun in [startSession]. */
    fun transport(sessionId: String): ConferenceMulticastRtpSrtpTransport? =
        sessions[sessionId]?.assembly?.pipeline?.transport

    fun catalog(sessionId: String): SourceBindingCatalog? = sessions[sessionId]?.catalog

    fun runtimeSnapshot(sessionId: String): SessionMediaRuntimeSnapshot? {
        val state = sessions[sessionId] ?: return null
        val authority = state.assembly.orchestrator.authority
        val pipeline = state.assembly.orchestrator.pipeline
        return SessionMediaRuntimeSnapshot(
            sessionId = sessionId,
            generation = state.fact.generation,
            ingressBlocked = state.ingressBlocked,
            transportScopeActive = state.assembly.pipeline.transport.isScopeActive(),
            catalogEntries = state.catalog.size(),
            admittedCount = authority.store.currentAdmitted().size,
            activeJitterSources = pipeline.activeJitterSourceCount(),
            liveDecoders = state.assembly.orchestrator.decodeMix.decoderPool.liveCount(),
            jitterBufferCount = pipeline.jitterBufferCount(),
        )
    }

    fun hasSession(sessionId: String): Boolean = sessionId in sessions

    fun isIngressBlocked(sessionId: String): Boolean = sessions[sessionId]?.ingressBlocked == true

    /**
     * First teardown step — fence ingress while session remains registered.
     * Idempotent; must precede RX disarm / runtime drain ([stopSession]).
     */
    fun beginSessionTeardown(
        sessionId: String,
        generation: Long,
    ): Boolean {
        val state = sessions[sessionId] ?: return false
        if (state.fact.generation != generation) return false
        if (state.ingressBlocked) return true
        state.ingressBlocked = true
        runtimeSnapshot(sessionId)?.let { snap ->
            Profile01ShadowRuntimeObservability.logRuntimeSnapshot(
                phase = "TEARDOWN_INGRESS_FENCED",
                sessionId = sessionId,
                conferenceId = Profile01ShadowRuntimeObservability.activeConferenceId,
                mediaKeyEpoch = state.fact.mediaKeyEpoch,
                snapshot = snap,
            )
        }
        return true
    }

    private fun revokeAndDrain(
        state: SessionState,
        moduleId: String,
        incarnationId: Long,
    ) {
        val authority = state.assembly.orchestrator.authority
        authority.revokeSource(moduleId, incarnationId)
        state.assembly.orchestrator.decodeMix.hardFence(moduleId, incarnationId)
        state.assembly.orchestrator.pipeline.drainSourceAfterSessionRevoke(moduleId)
    }

    private fun drainResidualDecoders(decodeMix: com.talkback.core.conference.runtime.DecodeMixRuntime) {
        for (slot in decodeMix.decoderPool.snapshot()) {
            decodeMix.hardFence(slot.sourceIdentity, slot.incarnationId)
        }
    }

    private fun ensurePlayoutStarted(state: SessionState) {
        if (!state.playoutStarted) {
            state.assembly.startPlayout()
            state.playoutStarted = true
        }
    }

    private fun assertRuntimeEmptyAfterStop(state: SessionState) {
        val authority = state.assembly.orchestrator.authority
        val pipeline = state.assembly.orchestrator.pipeline
        val transport = state.assembly.pipeline.transport
        require(!transport.isScopeActive()) { "transport scope still active after stopSession" }
        require(state.catalog.size() == 0) { "catalog not empty after stopSession" }
        require(authority.store.currentAdmitted().isEmpty()) {
            "admitted sources remain after stopSession"
        }
        require(pipeline.activeJitterSourceCount() == 0) {
            "jitter alloc not empty after stopSession"
        }
        require(pipeline.jitterBufferCount() == 0) {
            "jitter buffers not empty after stopSession"
        }
        require(state.assembly.orchestrator.decodeMix.decoderPool.liveCount() == 0) {
            "live decoders remain after stopSession"
        }
    }

    companion object {
        fun forProduct(context: Context): ConferenceSessionMediaWiring =
            ConferenceSessionMediaWiring(
                assemblyFactory = { ctx, _ -> ConferenceMulticastRealMediaAssembly.create(ctx!!) },
                appContext = context.applicationContext,
            )

        /** Phase A shadow — real underlay/multicast; no AudioTrack playout. */
        fun forShadow(context: Context): ConferenceSessionMediaWiring =
            ConferenceSessionMediaWiring(
                assemblyFactory = { ctx, sessionId ->
                    ConferenceMulticastRealMediaAssembly.createShadow(ctx!!, sessionId)
                },
                appContext = context.applicationContext,
            )

        fun forHarness(): ConferenceSessionMediaWiring =
            ConferenceSessionMediaWiring(
                assemblyFactory = { _, _ -> ConferenceMulticastRealMediaAssembly.createHarness() },
                appContext = null,
            )
    }
}
