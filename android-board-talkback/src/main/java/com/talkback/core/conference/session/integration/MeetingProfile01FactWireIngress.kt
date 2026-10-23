package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01MembershipGeneration
import com.talkback.core.conference.session.profile01.Profile01MembershipIngressResult
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.Profile01SignedFactDecodeResult
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Meeting control-plane wire → [Profile01ConferenceMediaFactIngress].
 *
 * No convergence / authority / lifecycle policy — decode, verify, publish only.
 * Failures are shadow-scoped; never propagate to ADR-0056 Meeting path.
 */
class MeetingProfile01FactWireIngress(
    private val ingress: Profile01ConferenceMediaFactIngress,
    private val supplementRegistry: Profile01SessionMediaSupplementRegistry,
    private val sessionIndex: MeetingProfile01ConferenceSessionIndex,
    private val preBindRetention: MeetingProfile01PreBindFactRetention,
    private val incompleteApplyContinuation: MeetingProfile01IncompleteApplyContinuation =
        MeetingProfile01IncompleteApplyContinuation(),
    private val networkInterfaceName: () -> String,
    private val localModuleId: () -> String,
    private val localEstablishmentKeyVersion: () -> Long,
    private val memberBindingMaterializer: Profile01ShadowMemberBindingMaterializer? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scheduleDelayed: (delayMs: Long, block: () -> Unit) -> Unit = { _, block -> block() },
) {
    val observability: MeetingProfile01FactWireObservability = MeetingProfile01FactWireObservability()

    var peerMembershipWiringReplay: Profile01PeerMembershipWiringReplay? = null

    /** Invoked after remote SOURCE_DECLARATION is wire-applied (peer post-bind consumption eligible). */
    var onPeerSourceDeclarationWireApplied: ((sessionId: String, remoteModuleId: String) -> Unit)? = null

    /** P13 — retained SOURCE drained and binding replay finished at converged mediaKeyEpoch. */
    var onPeerMediaKeyEpochSourceDrainComplete: ((sessionId: String) -> Unit)? = null

    /** P04 — peer membership replay deferred for supplement; host may republish MEDIA_KEY_PACKAGE. */
    var onPeerMembershipReplayDeferredNoSupplement:
        ((sessionId: String, conferenceId: String, mediaKeyEpoch: Long) -> Unit)? = null

    private val pendingMediaKeyAwaitingMembership = ConcurrentHashMap<String, ByteArray>()
    private val mediaKeyMembershipRetryAttempts = ConcurrentHashMap<String, Int>()
    private val membershipHeadFastRecoveryInFlight = ConcurrentHashMap.newKeySet<String>()
    private val pendingMembershipAwaitingCreation =
        ConcurrentHashMap<String, MutableList<ByteArray>>()
    private val pendingMembershipAwaitingCreationDigests =
        ConcurrentHashMap<String, MutableSet<String>>()

    companion object {
        private const val MAX_MEDIA_KEY_MEMBERSHIP_RETRY = 3
        private const val MAX_MEMBERSHIP_HEAD_FAST_RECOVERY_ATTEMPTS = 40
        private const val MEMBERSHIP_HEAD_FAST_RECOVERY_DELAY_MS = 25L
        private const val MAX_PENDING_MEMBERSHIP_AWAITING_CREATION = 16
        private val MEDIA_KEY_AWAIT_MEMBERSHIP_REASONS =
            setOf("MEMBERSHIP_CONTEXT_MISMATCH", "MEMBERSHIP_NOT_CONVERGED")
        private val MEMBERSHIP_AWAIT_CREATION_REASONS =
            setOf("CREATION_NOT_CONVERGED", "PREDECESSOR_NOT_CURRENT_HEAD")
    }

    fun onConferenceSignedFact(signal: SignalEnvelope): WireIngressOutcome {
        if (!MeetingProductMediaShadow.enabled) return WireIngressOutcome.SHADOW_DISABLED
        val sessionBinding = sessionIndex.bindingForSession(signal.sessionId)
        if (sessionBinding == null) {
            return handlePreBindSignedFact(signal)
        }
        return applySignedFact(signal, sessionBinding)
    }

    /**
     * Drain retained CREATION facts after Meeting session bridge bind.
     * Detach/consume before re-ingress.
     */
    fun drainRetainedAfterSessionBind(
        sessionId: String,
        onlyFactTypes: Set<Int>? = PRE_BIND_DRAIN_SESSION_BIND_FACT_TYPES,
    ): SessionBindDrainOutcome {
        val conferenceIdHex =
            runCatching { sessionIndex.ensureConferenceIdHex(sessionId) }
                .getOrNull()
                ?: return SessionBindDrainOutcome(0, 0, 0)
        val batch = preBindRetention.detachForDrain(conferenceIdHex, onlyFactTypes)
        preBindRetention.observability.logBindDrain(sessionId, conferenceIdHex, batch.size)
        var applied = 0
        var rejected = 0
        var fenced = 0
        for (detached in batch) {
            if (detached.signal.sessionId != sessionId) {
                fenced++
                preBindRetention.observability.logSessionDiscard(
                    sessionId = sessionId,
                    conferenceIdHex = detached.conferenceIdHex,
                    reason = DiscardReason.WRONG_SESSION,
                )
                continue
            }
            if (detached.conferenceIdHex != conferenceIdHex) {
                fenced++
                preBindRetention.observability.logSessionDiscard(
                    sessionId = sessionId,
                    conferenceIdHex = detached.conferenceIdHex,
                    reason = DiscardReason.WRONG_CONFERENCE,
                )
                continue
            }
            when (onConferenceSignedFact(detached.signal)) {
                WireIngressOutcome.APPLIED -> applied++
                else -> rejected++
            }
        }
        return SessionBindDrainOutcome(applied = applied, rejected = rejected, fenced = fenced)
    }

    fun onSessionUnregistered(sessionId: String) {
        pendingMediaKeyAwaitingMembership.remove(sessionId)
        mediaKeyMembershipRetryAttempts.remove(sessionId)
        pendingMembershipAwaitingCreation.remove(sessionId)
        pendingMembershipAwaitingCreationDigests.remove(sessionId)
        incompleteApplyContinuation.discardForSession(sessionId, IncompleteDiscardReason.SESSION_UNREGISTERED)
        val conferenceIdHex = sessionIndex.conferenceIdForSession(sessionId)
        if (conferenceIdHex != null) {
            fenceConferenceForAuthoritativeCatchUp(conferenceIdHex)
            preBindRetention.discardForConference(conferenceIdHex, DiscardReason.SESSION_UNREGISTERED)
            return
        }
        val derived =
            com.talkback.core.conference.session.profile01.wire.Profile01ConferenceIdAuthority
                .deriveId128Hex(sessionId)
        fenceConferenceForAuthoritativeCatchUp(derived)
        preBindRetention.discardForConference(
            derived,
            DiscardReason.SESSION_UNREGISTERED,
        )
    }

    private fun fenceConferenceForAuthoritativeCatchUp(conferenceIdHex: String) {
        ingress.membershipRegistry().fenceConferenceForAuthoritativeCatchUp(conferenceIdHex)
        ingress.fenceConferenceValidatorForAuthoritativeCatchUp(conferenceIdHex)
    }

    private fun handlePreBindSignedFact(signal: SignalEnvelope): WireIngressOutcome {
        val signedBytes = decodeSignedFactPayload(signal.payload)
        if (signedBytes == null) {
            observability.record(WireIngressOutcome.REJECTED_DECODE, signal.sessionId, null, "MALFORMED_PAYLOAD")
            return WireIngressOutcome.REJECTED_DECODE
        }
        val routing =
            Profile01WireCborDecoder.readRoutingIdentity(signedBytes)
                ?: run {
                    observability.record(WireIngressOutcome.REJECTED_DECODE, signal.sessionId, null, "UNKNOWN_FACT_TYPE")
                    return WireIngressOutcome.REJECTED_DECODE
                }
        if (
            routing.factType != Profile01WireConstants.FACT_TYPE_CREATION &&
            routing.factType != Profile01WireConstants.FACT_TYPE_MEMBERSHIP &&
            routing.factType != Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION &&
            routing.factType != Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE
        ) {
            observability.recordPreBindNotRetained(signal.sessionId, routing.factType, "UNAUTHORIZED_FACT_TYPE")
            return WireIngressOutcome.PRE_BIND_NOT_RETAINED
        }
        return when (
            preBindRetention.retain(
                conferenceIdHex = routing.conferenceIdHex,
                factDigestHex = routing.factDigestHex,
                signal = signal,
            )
        ) {
            RetainOutcome.RETAINED,
            RetainOutcome.DEDUPED,
            -> {
                observability.record(
                    WireIngressOutcome.DEFERRED_NO_SESSION_RETAINED,
                    signal.sessionId,
                    routing.factType,
                )
                WireIngressOutcome.DEFERRED_NO_SESSION_RETAINED
            }
            RetainOutcome.REJECTED_BOUNDS -> {
                observability.record(
                    WireIngressOutcome.REJECTED_PUBLISH,
                    signal.sessionId,
                    routing.factType,
                    "RETENTION_BOUNDS",
                )
                WireIngressOutcome.REJECTED_PUBLISH
            }
        }
    }

    private fun applySignedFact(
        signal: SignalEnvelope,
        sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding,
    ): WireIngressOutcome {
        val signedBytes = decodeSignedFactPayload(signal.payload)
        if (signedBytes == null) {
            observability.record(WireIngressOutcome.REJECTED_DECODE, signal.sessionId, null, "MALFORMED_PAYLOAD")
            return WireIngressOutcome.REJECTED_DECODE
        }
        val factType =
            Profile01WireCborDecoder.readFactType(signedBytes)
                ?: run {
                    observability.record(WireIngressOutcome.REJECTED_DECODE, signal.sessionId, null, "UNKNOWN_FACT_TYPE")
                    return WireIngressOutcome.REJECTED_DECODE
                }
        return try {
            routeFact(factType, signedBytes, signal, sessionBinding)
        } catch (t: Throwable) {
            observability.recordFailed(signal.sessionId, factType, t)
            WireIngressOutcome.SHADOW_FAILED
        }
    }

    private fun routeFact(
        factType: Int,
        signedBytes: ByteArray,
        signal: SignalEnvelope,
        sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding,
    ): WireIngressOutcome {
        return when (factType) {
            Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE ->
                ingestMediaKeyPackage(signedBytes, signal.sessionId)
            Profile01WireConstants.FACT_TYPE_MEMBERSHIP ->
                ingestMembership(signedBytes, signal, sessionBinding)
            Profile01WireConstants.FACT_TYPE_CREATION ->
                ingestCreation(signedBytes, signal, sessionBinding)
            Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION ->
                ingestSourceDeclaration(signedBytes, signal, sessionBinding)
            else -> {
                observability.record(
                    WireIngressOutcome.REJECTED_UNSUPPORTED,
                    signal.sessionId,
                    factType,
                    "UNSUPPORTED_FACT_TYPE",
                )
                WireIngressOutcome.REJECTED_UNSUPPORTED
            }
        }
    }

    private fun ingestMediaKeyPackage(
        signedBytes: ByteArray,
        sessionId: String,
    ): WireIngressOutcome {
        val result =
            ingress.ingestMediaKeyPackageSignedFact(
                signedBytes,
                localModuleId(),
                localEstablishmentKeyVersion(),
            )
        return when (result) {
            is com.talkback.core.conference.session.profile01.Profile01MediaKeyPackageIngressResult.Ready -> {
                drainSatisfiableIncompleteCreations(
                    conferenceIdHex = result.material.conferenceId,
                    triggerMediaKeyEpoch = result.material.mediaKeyEpoch,
                )
                maybeMaterializeSessionAtMembershipHead(
                    sessionId = sessionId,
                    conferenceIdHex = result.material.conferenceId,
                    sessionBinding = sessionIndex.bindingForSession(sessionId),
                )
                peerMembershipWiringReplay?.replayAfterMediaKeyAppliedIfMembershipReady(sessionId)
                finalizePeerMediaKeyEpochConsumption(sessionId)
                observability.record(WireIngressOutcome.APPLIED, sessionId, Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE)
                WireIngressOutcome.APPLIED
            }
            is com.talkback.core.conference.session.profile01.Profile01MediaKeyPackageIngressResult.Rejected -> {
                if (result.reason in MEDIA_KEY_AWAIT_MEMBERSHIP_REASONS) {
                    pendingMediaKeyAwaitingMembership[sessionId] = signedBytes.copyOf()
                    observability.recordMediaKeyAwaitingMembership(
                        sessionId = sessionId,
                        reason = result.reason,
                    )
                }
                observability.record(
                    WireIngressOutcome.REJECTED_VERIFY,
                    sessionId,
                    Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE,
                    result.reason,
                )
                WireIngressOutcome.REJECTED_VERIFY
            }
        }
    }

    private enum class RetainPendingMembershipOutcome {
        RETAINED,
        DEDUPED,
        REJECTED_BOUNDS,
    }

    private fun retainPendingMembershipAwaitingCreation(
        sessionId: String,
        signedBytes: ByteArray,
    ): RetainPendingMembershipOutcome {
        val routing =
            Profile01WireCborDecoder.readRoutingIdentity(signedBytes)
                ?: return RetainPendingMembershipOutcome.REJECTED_BOUNDS
        val digestHex = routing.factDigestHex
        val digests =
            pendingMembershipAwaitingCreationDigests.getOrPut(sessionId) {
                ConcurrentHashMap.newKeySet()
            }
        if (!digests.add(digestHex)) {
            return RetainPendingMembershipOutcome.DEDUPED
        }
        val list =
            pendingMembershipAwaitingCreation.getOrPut(sessionId) {
                mutableListOf()
            }
        if (list.size >= MAX_PENDING_MEMBERSHIP_AWAITING_CREATION) {
            digests.remove(digestHex)
            return RetainPendingMembershipOutcome.REJECTED_BOUNDS
        }
        list.add(signedBytes.copyOf())
        observability.recordMembershipPendingQueued(sessionId, routing.factType)
        return RetainPendingMembershipOutcome.RETAINED
    }

    private fun replayPendingMembershipAwaitingCreation(
        sessionId: String,
        sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding,
    ) {
        val pending = pendingMembershipAwaitingCreation.remove(sessionId) ?: return
        pendingMembershipAwaitingCreationDigests.remove(sessionId)
        if (pending.isEmpty()) return
        observability.recordMembershipPendingReplay(sessionId, pending.size)
        val sorted =
            pending.sortedBy { bytes ->
                when (val decoded = Profile01WireCborDecoder.decodeMembership(bytes)) {
                    is Profile01WireCborDecoder.DecodeResult.Ready -> decoded.value.membershipVersion
                    is Profile01WireCborDecoder.DecodeResult.Rejected -> Long.MAX_VALUE
                }
            }
        for (bytes in sorted) {
            val signal =
                SignalEnvelope(
                    type = SignalType.CONFERENCE_SIGNED_FACT,
                    from = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
                    to = null,
                    sessionId = sessionId,
                    timestampMs = clock(),
                    payload = Base64.getEncoder().encodeToString(bytes),
                    nonce = "membership-pending-replay",
                    signature = "membership-pending-replay",
                )
            ingestMembership(bytes, signal, sessionBinding)
        }
    }

    private fun advanceMembershipHeadAfterCreationApplied(
        sessionId: String,
        sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding,
    ) {
        val conferenceIdHex = sessionIndex.conferenceIdForSession(sessionId) ?: return
        val generation = ingress.currentMembershipGeneration(conferenceIdHex) ?: return
        if (generation.membershipVersion <= sessionBinding.rosterEpoch) {
            return
        }
        sessionIndex.updateRosterEpoch(sessionId, generation.membershipVersion)
        val updatedBinding = sessionBinding.copy(rosterEpoch = generation.membershipVersion)
        maybeMaterializeSessionAtMembershipHead(
            sessionId = sessionId,
            conferenceIdHex = conferenceIdHex,
            sessionBinding = updatedBinding,
        )
        onMembershipHeadApplied(sessionId, updatedBinding)
    }

    private fun retryPendingMediaKeyAfterMembership(sessionId: String) {
        val pending = pendingMediaKeyAwaitingMembership.remove(sessionId) ?: return
        val nextAttempt = (mediaKeyMembershipRetryAttempts[sessionId] ?: 0) + 1
        if (nextAttempt > MAX_MEDIA_KEY_MEMBERSHIP_RETRY) {
            mediaKeyMembershipRetryAttempts.remove(sessionId)
            observability.recordMediaKeyPendingDropped(sessionId, "MAX_RETRIES")
            return
        }
        mediaKeyMembershipRetryAttempts[sessionId] = nextAttempt
        observability.recordMediaKeyPendingRetry(sessionId, nextAttempt)
        when (ingestMediaKeyPackage(pending, sessionId)) {
            WireIngressOutcome.APPLIED -> {
                mediaKeyMembershipRetryAttempts.remove(sessionId)
                observability.recordMediaKeyPendingApplied(sessionId)
            }
            else -> Unit
        }
    }

    private fun ingestMembership(
        signedBytes: ByteArray,
        signal: SignalEnvelope,
        sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding,
    ): WireIngressOutcome {
        return when (val result = ingress.ingestMembershipSignedFact(signedBytes)) {
            is Profile01MembershipIngressResult.Converged -> {
                sessionIndex.bindConferenceId(signal.sessionId, result.generation.conferenceId)
                sessionIndex.updateRosterEpoch(signal.sessionId, result.generation.membershipVersion)
                observability.record(WireIngressOutcome.APPLIED, signal.sessionId, Profile01WireConstants.FACT_TYPE_MEMBERSHIP)
                val updatedBinding = sessionBinding.copy(rosterEpoch = result.generation.membershipVersion)
                maybeMaterializeSessionAtMembershipHead(
                    sessionId = signal.sessionId,
                    conferenceIdHex = result.generation.conferenceId,
                    sessionBinding = updatedBinding,
                )
                onMembershipHeadApplied(signal.sessionId, updatedBinding)
                WireIngressOutcome.APPLIED
            }
            is Profile01MembershipIngressResult.Pending -> {
                if (result.reason in MEMBERSHIP_AWAIT_CREATION_REASONS) {
                    when (retainPendingMembershipAwaitingCreation(signal.sessionId, signedBytes)) {
                        RetainPendingMembershipOutcome.RETAINED,
                        RetainPendingMembershipOutcome.DEDUPED,
                        -> {
                            observability.record(
                                WireIngressOutcome.DEFERRED_NO_SESSION,
                                signal.sessionId,
                                Profile01WireConstants.FACT_TYPE_MEMBERSHIP,
                                result.reason,
                            )
                            return WireIngressOutcome.DEFERRED_NO_SESSION
                        }
                        RetainPendingMembershipOutcome.REJECTED_BOUNDS ->
                            observability.recordMembershipPendingDropped(
                                signal.sessionId,
                                "PENDING_MEMBERSHIP_BOUNDS",
                            )
                    }
                }
                observability.record(
                    WireIngressOutcome.REJECTED_PUBLISH,
                    signal.sessionId,
                    Profile01WireConstants.FACT_TYPE_MEMBERSHIP,
                    result.reason,
                )
                WireIngressOutcome.REJECTED_PUBLISH
            }
            is Profile01MembershipIngressResult.Rejected -> {
                observability.record(
                    WireIngressOutcome.REJECTED_VERIFY,
                    signal.sessionId,
                    Profile01WireConstants.FACT_TYPE_MEMBERSHIP,
                    result.reason,
                )
                WireIngressOutcome.REJECTED_VERIFY
            }
        }
    }

    private fun ingestCreation(
        signedBytes: ByteArray,
        signal: SignalEnvelope,
        sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding,
    ): WireIngressOutcome {
        val placeholder =
            Profile01SessionMediaSupplement(
                channelId = sessionBinding.channelId,
                masterKey = ByteArray(32),
                masterSalt = ByteArray(12),
                keyContextHint64 = ByteArray(8),
            )
        val result =
            ingress.ingestCreationSignedFact(
                signedBytes,
                placeholder,
                networkInterfaceName(),
            )
        if (result.decode is Profile01SignedFactDecodeResult.Rejected) {
            observability.record(
                WireIngressOutcome.REJECTED_DECODE,
                signal.sessionId,
                Profile01WireConstants.FACT_TYPE_CREATION,
                (result.decode as Profile01SignedFactDecodeResult.Rejected).reason,
            )
            return WireIngressOutcome.REJECTED_DECODE
        }
        val publishOutcome = result.ingress?.publishOutcome
        val validation =
            result.ingress?.validation as? com.talkback.core.conference.session.profile01.Profile01ValidationResult.ReadySession
        val conferenceId = validation?.declaration?.conferenceId
        if (conferenceId != null) {
            sessionIndex.bindConferenceId(signal.sessionId, conferenceId)
        }
        if (
            validation != null &&
                publishOutcome == ControlFactPublishOutcome.REJECTED_INCOMPLETE &&
                isMissingMediaSupplement(
                    conferenceIdHex = validation.declaration.conferenceId,
                    mediaKeyEpoch = validation.declaration.mediaKeyEpoch,
                    declaration = validation.declaration,
                )
        ) {
            val routing =
                Profile01WireCborDecoder.readRoutingIdentity(signedBytes)
                    ?: return WireIngressOutcome.REJECTED_DECODE
            when (
                incompleteApplyContinuation.retainPending(
                    MeetingProfile01IncompleteApplyContinuation.PendingIncompleteCreationApply(
                        sessionId = signal.sessionId,
                        conferenceIdHex = validation.declaration.conferenceId,
                        mediaKeyEpoch = validation.declaration.mediaKeyEpoch,
                        factDigestHex = routing.factDigestHex,
                        signedFactBytes = signedBytes.copyOf(),
                        networkInterfaceName = networkInterfaceName(),
                        channelId = sessionBinding.channelId,
                        retainedAtMs = clock(),
                    ),
                )
            ) {
                RetainPendingOutcome.RETAINED,
                RetainPendingOutcome.DEDUPED,
                -> {
                    observability.record(
                        WireIngressOutcome.INCOMPLETE_PENDING,
                        signal.sessionId,
                        Profile01WireConstants.FACT_TYPE_CREATION,
                        "MISSING_MEDIA_SUPPLEMENT",
                    )
                    // CREATION incomplete: drain retained PACKAGE only; SOURCE after mediaKeyEpoch aligns.
                    drainRetainedAfterSessionBind(
                        signal.sessionId,
                        PRE_BIND_DRAIN_MEMBERSHIP_HEAD_FACT_TYPES,
                    )
                    return WireIngressOutcome.INCOMPLETE_PENDING
                }
                RetainPendingOutcome.REJECTED_BOUNDS ->
                    observability.record(
                        WireIngressOutcome.REJECTED_PUBLISH,
                        signal.sessionId,
                        Profile01WireConstants.FACT_TYPE_CREATION,
                        "INCOMPLETE_BOUNDS",
                    ).let { WireIngressOutcome.REJECTED_PUBLISH }
            }
        }
        return recordPublishAndReplay(
            signal.sessionId,
            Profile01WireConstants.FACT_TYPE_CREATION,
            publishOutcome,
            sessionBinding,
            moduleId = null,
            publishedMembershipVersion = validation?.declaration?.membershipVersion,
        )
    }

    private fun ingestSourceDeclaration(
        signedBytes: ByteArray,
        signal: SignalEnvelope,
        sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding,
    ): WireIngressOutcome {
        val result = ingress.ingestSourceDeclarationSignedFact(signedBytes)
        if (result.decode is Profile01SignedFactDecodeResult.Rejected) {
            observability.record(
                WireIngressOutcome.REJECTED_DECODE,
                signal.sessionId,
                Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION,
                (result.decode as Profile01SignedFactDecodeResult.Rejected).reason,
            )
            return WireIngressOutcome.REJECTED_DECODE
        }
        val moduleId =
            (result.ingress?.validation as? com.talkback.core.conference.session.profile01.Profile01ValidationResult.ReadyMember)
                ?.declaration
                ?.moduleId
        return recordPublishAndReplay(
            signal.sessionId,
            Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION,
            result.ingress?.publishOutcome,
            sessionBinding,
            moduleId = moduleId,
        )
    }

    private fun isMissingMediaSupplement(
        conferenceIdHex: String,
        mediaKeyEpoch: Long,
        declaration: com.talkback.core.conference.session.gbc.AuthoritativeConferenceMediaSessionDeclaration,
    ): Boolean {
        if (supplementRegistry.lookup(conferenceIdHex, mediaKeyEpoch) != null) return false
        return !com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
            .validateSessionComplete(declaration)
    }

    internal fun drainIncompleteAfterSupplementReadyForTest(
        conferenceIdHex: String,
        mediaKeyEpoch: Long,
    ) {
        drainSatisfiableIncompleteCreations(conferenceIdHex, mediaKeyEpoch)
    }

    fun drainSatisfiableIncompleteCreations(
        conferenceIdHex: String,
        triggerMediaKeyEpoch: Long,
    ) {
        val detached =
            incompleteApplyContinuation.detachSatisfiable(
                conferenceIdHex = conferenceIdHex,
                hasSupplementAtEpoch = { epoch ->
                    supplementRegistry.lookup(conferenceIdHex, epoch) != null
                },
                triggerMediaKeyEpoch = triggerMediaKeyEpoch,
            )
        for (entry in detached) {
            reapplyDetachedIncompleteCreation(entry.pending)
        }
    }

    /** RCA4b — supplement-ready continuation: drain satisfiable CREATION + membership-head materialize. */
    fun onSupplementReadyForConference(
        conferenceIdHex: String,
        triggerMediaKeyEpoch: Long,
    ) {
        drainSatisfiableIncompleteCreations(conferenceIdHex, triggerMediaKeyEpoch)
        val sessionId = sessionIndex.sessionIdForConference(conferenceIdHex) ?: return
        maybeMaterializeSessionAtMembershipHead(
            sessionId = sessionId,
            conferenceIdHex = conferenceIdHex,
            sessionBinding = sessionIndex.bindingForSession(sessionId),
        )
        retryPendingMediaKeyAfterMembership(sessionId)
        peerMembershipWiringReplay?.replayAfterMediaKeyAppliedIfMembershipReady(sessionId)
    }

    private fun maybeMaterializeSessionAtMembershipHead(
        sessionId: String,
        conferenceIdHex: String,
        sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding?,
    ) {
        if (sessionBinding == null) return
        val pendingBytes =
            incompleteApplyContinuation.peekNewestPending(conferenceIdHex)?.signedFactBytes
        val outcome =
            ingress.tryMaterializeSessionAtMembershipHead(
                conferenceId = conferenceIdHex,
                channelId = sessionBinding.channelId,
                networkInterfaceName = networkInterfaceName(),
                creationSignedBytesForEndpoint = pendingBytes,
            )
        if (outcome == com.talkback.core.conference.session.profile01.MembershipHeadMaterializationOutcome.APPLIED) {
            val generation = ingress.currentMembershipGeneration(conferenceIdHex)
            if (generation != null) {
                incompleteApplyContinuation.discardBelowMediaKeyEpoch(
                    conferenceIdHex = conferenceIdHex,
                    mediaKeyEpoch = generation.mediaKeyEpoch,
                    reason = IncompleteDiscardReason.GENERATION_FENCE,
                )
                sessionIndex.updateRosterEpoch(sessionId, generation.membershipVersion)
                // Registry published at membership head — must replay shadow SESSION_STARTED
                // (same as CREATION APPLIED path); otherwise SHADOW_SESSION_STARTED never fires.
                replaySessionStarted(
                    sessionId,
                    sessionBinding.copy(rosterEpoch = generation.membershipVersion),
                    generation.membershipVersion,
                )
            }
            finalizePeerMediaKeyEpochConsumption(sessionId)
            memberBindingMaterializer?.onCreationWireApplied(sessionId)
        }
    }

    /**
     * P12 — drain retained SOURCE only after membership head and wiring mediaKeyEpoch converge.
     * Must run before authoritative binding replay at the new epoch.
     */
    private fun finalizePeerMediaKeyEpochConsumption(sessionId: String) {
        drainRetainedSourceAfterMediaKeyEpochAligned(sessionId)
        peerMembershipWiringReplay?.replayMemberBindingsAfterMediaKeyEpochRotated(sessionId)
        onPeerMediaKeyEpochSourceDrainComplete?.invoke(sessionId)
    }

    private fun drainRetainedSourceAfterMediaKeyEpochAligned(sessionId: String) {
        if (ConferenceSessionMediaBridge.wiring == null) return
        val sessionBinding = sessionIndex.bindingForSession(sessionId) ?: return
        val conferenceIdHex =
            sessionBinding.conferenceId
                ?: sessionIndex.conferenceIdForSession(sessionId)
                ?: return
        val generation = ingress.currentMembershipGeneration(conferenceIdHex) ?: return
        val wiringEpoch = ConferenceSessionMediaBridge.currentMediaKeyEpoch(sessionId) ?: return
        if (wiringEpoch != generation.mediaKeyEpoch) return
        drainRetainedAfterSessionBind(sessionId, PRE_BIND_DRAIN_SOURCE_FACT_TYPES)
    }

    private fun reapplyDetachedIncompleteCreation(
        pending: MeetingProfile01IncompleteApplyContinuation.PendingIncompleteCreationApply,
    ) {
        val binding = sessionIndex.bindingForSession(pending.sessionId)
        if (binding == null) {
            incompleteApplyContinuation.observability.logDiscarded(pending, IncompleteDiscardReason.WRONG_SESSION)
            return
        }
        if (binding.conferenceId != null && binding.conferenceId != pending.conferenceIdHex) {
            incompleteApplyContinuation.observability.logDiscarded(pending, IncompleteDiscardReason.WRONG_CONFERENCE)
            return
        }
        if (binding.channelId != pending.channelId) {
            incompleteApplyContinuation.observability.logDiscarded(pending, IncompleteDiscardReason.GENERATION_FENCE)
            return
        }
        val signal =
            SignalEnvelope(
                type = SignalType.CONFERENCE_SIGNED_FACT,
                from = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
                to = null,
                sessionId = pending.sessionId,
                timestampMs = clock(),
                payload = java.util.Base64.getEncoder().encodeToString(pending.signedFactBytes),
                nonce = "incomplete-drain-nonce",
                signature = "incomplete-drain-signature",
            )
        ingestCreation(pending.signedFactBytes, signal, binding)
    }

    private fun recordPublishAndReplay(
        sessionId: String,
        factType: Int,
        publishOutcome: ControlFactPublishOutcome?,
        sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding,
        moduleId: String?,
        publishedMembershipVersion: Long? = null,
    ): WireIngressOutcome {
        val applied =
            publishOutcome == ControlFactPublishOutcome.ACCEPTED ||
                publishOutcome == ControlFactPublishOutcome.IDEMPOTENT
        if (!applied) {
            val reason = publishOutcome?.name ?: "NOT_PUBLISHED"
            observability.record(
                when (publishOutcome) {
                    ControlFactPublishOutcome.REJECTED_STALE,
                    ControlFactPublishOutcome.REJECTED_SUPERSEDED,
                    -> WireIngressOutcome.REJECTED_STALE
                    else -> WireIngressOutcome.REJECTED_PUBLISH
                },
                sessionId,
                factType,
                reason,
            )
            return when (publishOutcome) {
                ControlFactPublishOutcome.REJECTED_STALE,
                ControlFactPublishOutcome.REJECTED_SUPERSEDED,
                -> WireIngressOutcome.REJECTED_STALE
                else -> WireIngressOutcome.REJECTED_PUBLISH
            }
        }
        observability.record(WireIngressOutcome.APPLIED, sessionId, factType)
        replayAfterPublish(
            sessionId,
            factType,
            sessionBinding,
            moduleId,
            publishedMembershipVersion,
            publishOutcome,
        )
        return WireIngressOutcome.APPLIED
    }

    /** P05 — pending ingest before replay; bounded fast recovery before P04 channel fallback. */
    private fun onMembershipHeadApplied(
        sessionId: String,
        sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding,
    ) {
        // Field RCA P07: package retained pre-bind must drain here when CREATION was incomplete.
        drainRetainedAfterSessionBind(sessionId, PRE_BIND_DRAIN_MEMBERSHIP_HEAD_FACT_TYPES)
        retryPendingMediaKeyAfterMembership(sessionId)
        val initialOutcome = attemptPeerMembershipWiringReplay(sessionId)
        if (initialOutcome == PeerMembershipWiringReplayOutcome.DEFERRED_NO_SUPPLEMENT) {
            scheduleMembershipHeadFastRecovery(sessionId, sessionBinding, attempt = 1)
        } else {
            finalizeMembershipReplay(sessionId, sessionBinding, initialOutcome, notifyDeferredNoSupplement = false)
        }
    }

    private fun scheduleMembershipHeadFastRecovery(
        sessionId: String,
        sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding,
        attempt: Int,
    ) {
        if (!membershipHeadFastRecoveryInFlight.add(sessionId)) {
            return
        }
        observability.recordMembershipHeadFastRecoveryScheduled(sessionId, attempt)
        scheduleDelayed(MEMBERSHIP_HEAD_FAST_RECOVERY_DELAY_MS) {
            runMembershipHeadFastRecoveryTick(sessionId, sessionBinding, attempt)
        }
    }

    private fun runMembershipHeadFastRecoveryTick(
        sessionId: String,
        sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding,
        attempt: Int,
    ) {
        if (!membershipHeadFastRecoveryInFlight.contains(sessionId)) {
            return
        }
        observability.recordMembershipHeadFastRecoveryTick(sessionId, attempt)
        drainRetainedAfterSessionBind(sessionId, PRE_BIND_DRAIN_MEMBERSHIP_HEAD_FACT_TYPES)
        retryPendingMediaKeyAfterMembership(sessionId)
        peerMembershipWiringReplay?.replayAfterMediaKeyAppliedIfMembershipReady(sessionId)
        val outcome = attemptPeerMembershipWiringReplay(sessionId)
        when {
            outcome != PeerMembershipWiringReplayOutcome.DEFERRED_NO_SUPPLEMENT -> {
                membershipHeadFastRecoveryInFlight.remove(sessionId)
                finalizeMembershipReplay(sessionId, sessionBinding, outcome, notifyDeferredNoSupplement = false)
            }
            attempt >= MAX_MEMBERSHIP_HEAD_FAST_RECOVERY_ATTEMPTS -> {
                membershipHeadFastRecoveryInFlight.remove(sessionId)
                finalizeMembershipReplay(
                    sessionId,
                    sessionBinding,
                    outcome,
                    notifyDeferredNoSupplement = true,
                )
            }
            else ->
                scheduleDelayed(MEMBERSHIP_HEAD_FAST_RECOVERY_DELAY_MS) {
                    runMembershipHeadFastRecoveryTick(sessionId, sessionBinding, attempt + 1)
                }
        }
    }

    private fun attemptPeerMembershipWiringReplay(sessionId: String): PeerMembershipWiringReplayOutcome? =
        peerMembershipWiringReplay?.replayAfterMembershipWireApplied(sessionId)

    private fun finalizeMembershipReplay(
        sessionId: String,
        sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding,
        replayOutcome: PeerMembershipWiringReplayOutcome?,
        notifyDeferredNoSupplement: Boolean,
    ) {
        if (notifyDeferredNoSupplement && replayOutcome == PeerMembershipWiringReplayOutcome.DEFERRED_NO_SUPPLEMENT) {
            val conferenceId = sessionIndex.conferenceIdForSession(sessionId)
            val mediaKeyEpoch = conferenceId?.let { ingress.currentMembershipGeneration(it)?.mediaKeyEpoch }
            if (conferenceId != null && mediaKeyEpoch != null) {
                observability.recordPeerReplayDeferredNoSupplement(sessionId, mediaKeyEpoch)
                onPeerMembershipReplayDeferredNoSupplement?.invoke(sessionId, conferenceId, mediaKeyEpoch)
            }
        }
        when (replayOutcome) {
            PeerMembershipWiringReplayOutcome.APPLIED,
            null,
            -> Unit
            else -> {
                replaySessionStarted(sessionId, sessionBinding, sessionBinding.rosterEpoch)
                sessionBinding.mediaConnectedModules.forEach { moduleId ->
                    memberBindingMaterializer?.onSourceDeclarationWireApplied(sessionId, moduleId)
                        ?: ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(
                            sessionId,
                            moduleId,
                        )
                }
                memberBindingMaterializer?.materializeLocalIfReady(sessionId)
            }
        }
    }

    private fun replayAfterPublish(
        sessionId: String,
        factType: Int,
        sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding,
        moduleId: String?,
        publishedMembershipVersion: Long? = null,
        publishOutcome: ControlFactPublishOutcome? = null,
    ) {
        when (factType) {
            Profile01WireConstants.FACT_TYPE_CREATION -> {
                val membershipVersionForRead =
                    publishedMembershipVersion
                        ?: error("CREATION replay requires published membershipVersion")
                replaySessionStarted(sessionId, sessionBinding, membershipVersionForRead)
                replayPendingMembershipAwaitingCreation(sessionId, sessionBinding)
                advanceMembershipHeadAfterCreationApplied(sessionId, sessionBinding)
                // After CREATION head is live: drain retained PACKAGE; SOURCE via mediaKeyEpoch alignment.
                drainRetainedAfterSessionBind(sessionId, PRE_BIND_DRAIN_MEMBERSHIP_HEAD_FACT_TYPES)
                finalizePeerMediaKeyEpochConsumption(sessionId)
                memberBindingMaterializer?.onCreationWireApplied(sessionId)
            }
            Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION ->
                moduleId?.let { id ->
                    ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberReplaced(sessionId, id)
                    memberBindingMaterializer?.onSourceDeclarationWireApplied(sessionId, id)
                        ?: if (id in sessionBinding.mediaConnectedModules) {
                            ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, id)
                        } else {
                            Unit
                        }
                    if (
                        id != localModuleId() &&
                        publishOutcome == ControlFactPublishOutcome.ACCEPTED
                    ) {
                        onPeerSourceDeclarationWireApplied?.invoke(sessionId, id)
                    }
                }
        }
    }

    private fun replaySessionStarted(
        sessionId: String,
        sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding,
        membershipVersionForRead: Long,
    ) {
        Profile01ShadowSessionReplay.replaySessionStarted(
            sessionId = sessionId,
            channelId = sessionBinding.channelId,
            membershipVersionForRead = membershipVersionForRead,
        )
    }

    private fun decodeSignedFactPayload(payload: String): ByteArray? =
        runCatching {
            val trimmed = payload.trim()
            if (trimmed.isEmpty()) return null
            val bytes = Base64.getDecoder().decode(trimmed)
            if (bytes.isEmpty() || bytes.size > Profile01WireConstants.MAX_SIGNED_FACT_BYTES) null else bytes
        }.getOrNull()

    fun currentMembershipGenerationForSession(sessionId: String): Profile01MembershipGeneration? {
        val conferenceId = sessionIndex.conferenceIdForSession(sessionId) ?: return null
        return ingress.currentMembershipGeneration(conferenceId)
    }

}

private val PRE_BIND_DRAIN_SESSION_BIND_FACT_TYPES: Set<Int> =
    setOf(
        Profile01WireConstants.FACT_TYPE_CREATION,
        Profile01WireConstants.FACT_TYPE_MEMBERSHIP,
    )

private val PRE_BIND_DRAIN_MEMBERSHIP_HEAD_FACT_TYPES: Set<Int> =
    setOf(Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE)

private val PRE_BIND_DRAIN_SOURCE_FACT_TYPES: Set<Int> =
    setOf(Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION)

enum class WireIngressOutcome {
    APPLIED,
    INCOMPLETE_PENDING,
    REJECTED_DECODE,
    REJECTED_VERIFY,
    REJECTED_PUBLISH,
    REJECTED_STALE,
    REJECTED_UNSUPPORTED,
    DEFERRED_NO_SESSION,
    DEFERRED_NO_SESSION_RETAINED,
    PRE_BIND_NOT_RETAINED,
    SHADOW_DISABLED,
    SHADOW_FAILED,
}

data class SessionBindDrainOutcome(
    val applied: Int,
    val rejected: Int,
    val fenced: Int,
)

class MeetingProfile01FactWireObservability {
    private val outcomeCounts = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private val lastEvents = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val failures = AtomicLong()

    fun record(
        outcome: WireIngressOutcome,
        sessionId: String,
        factType: Int?,
        detail: String? = null,
    ) {
        outcomeCounts.getOrPut(outcome.name) { AtomicLong() }.incrementAndGet()
        val key = "$sessionId:${factType ?: "-"}"
        lastEvents[key] =
            if (detail != null) {
                "${outcome.name}:$detail"
            } else {
                outcome.name
            }
        logInfo("WIRE_INGRESS session=$sessionId factType=$factType outcome=$outcome detail=$detail")
    }

    fun recordMediaKeyAwaitingMembership(
        sessionId: String,
        reason: String,
    ) {
        logInfo(
            "PROFILE01_MEDIA_KEY_PENDING_QUEUED session=$sessionId reason=$reason",
        )
    }

    fun recordMediaKeyPendingRetry(
        sessionId: String,
        attempt: Int,
    ) {
        logInfo("PROFILE01_MEDIA_KEY_PENDING_RETRY session=$sessionId attempt=$attempt")
    }

    fun recordMediaKeyPendingApplied(sessionId: String) {
        logInfo("PROFILE01_MEDIA_KEY_PENDING_APPLIED session=$sessionId")
    }

    fun recordMediaKeyPendingDropped(
        sessionId: String,
        reason: String,
    ) {
        logInfo("PROFILE01_MEDIA_KEY_PENDING_DROPPED session=$sessionId reason=$reason")
    }

    fun recordMembershipPendingQueued(
        sessionId: String,
        factType: Int,
    ) {
        logInfo(
            "PROFILE01_MEMBERSHIP_PENDING_QUEUED session=$sessionId factType=$factType " +
                "reason=AWAIT_CREATION",
        )
    }

    fun recordMembershipPendingReplay(
        sessionId: String,
        count: Int,
    ) {
        logInfo("PROFILE01_MEMBERSHIP_PENDING_REPLAY session=$sessionId count=$count")
    }

    fun recordMembershipPendingDropped(
        sessionId: String,
        reason: String,
    ) {
        logInfo("PROFILE01_MEMBERSHIP_PENDING_DROPPED session=$sessionId reason=$reason")
    }

    fun recordPeerReplayDeferredNoSupplement(
        sessionId: String,
        mediaKeyEpoch: Long,
    ) {
        logInfo(
            "PROFILE01_PEER_REPLAY_DEFERRED_NO_SUPPLEMENT session=$sessionId mediaKeyEpoch=$mediaKeyEpoch",
        )
    }

    fun recordMembershipHeadFastRecoveryScheduled(
        sessionId: String,
        attempt: Int,
    ) {
        logInfo(
            "PROFILE01_MEMBERSHIP_HEAD_FAST_RECOVERY_SCHEDULED session=$sessionId attempt=$attempt",
        )
    }

    fun recordMembershipHeadFastRecoveryTick(
        sessionId: String,
        attempt: Int,
    ) {
        logInfo(
            "PROFILE01_MEMBERSHIP_HEAD_FAST_RECOVERY_TICK session=$sessionId attempt=$attempt",
        )
    }

    fun recordPreBindNotRetained(
        sessionId: String,
        factType: Int,
        reason: String,
    ) {
        outcomeCounts.getOrPut(WireIngressOutcome.PRE_BIND_NOT_RETAINED.name) { AtomicLong() }.incrementAndGet()
        val key = "$sessionId:$factType"
        lastEvents[key] = "${WireIngressOutcome.PRE_BIND_NOT_RETAINED.name}:$reason"
        logInfo("PRE_BIND_NOT_RETAINED session=$sessionId factType=$factType reason=$reason")
    }

    fun recordFailed(
        sessionId: String,
        factType: Int,
        error: Throwable,
    ) {
        failures.incrementAndGet()
        lastEvents["$sessionId:$factType"] = "SHADOW_FAILED:${error.javaClass.simpleName}"
        logWarn("WIRE_INGRESS_FAILED session=$sessionId factType=$factType: ${error.message}", error)
    }

    fun snapshot(): Map<String, Any> =
        mapOf(
            "evidenceScope" to MeetingProductMediaShadow.EVIDENCE_SCOPE,
            "outcomeCounts" to outcomeCounts.mapValues { it.value.get() },
            "failureCount" to failures.get(),
            "recentEvents" to lastEvents.toMap(),
        )

    private fun logInfo(message: String) {
        try {
            android.util.Log.i(MeetingProductMediaShadow.LOG_TAG, message)
        } catch (_: Throwable) {
        }
    }

    private fun logWarn(
        message: String,
        error: Throwable? = null,
    ) {
        try {
            if (error != null) {
                android.util.Log.w(MeetingProductMediaShadow.LOG_TAG, message, error)
            } else {
                android.util.Log.w(MeetingProductMediaShadow.LOG_TAG, message)
            }
        } catch (_: Throwable) {
        }
    }
}
