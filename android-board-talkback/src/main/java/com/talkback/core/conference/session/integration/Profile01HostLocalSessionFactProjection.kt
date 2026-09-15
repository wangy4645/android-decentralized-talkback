package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01MembershipIngressResult
import com.talkback.core.conference.session.profile01.SessionHeadSyncOutcome
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.Profile01SignedFactDecodeResult
import com.talkback.core.conference.session.profile01.wire.Profile01CborCodec
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder

/**
 * PR-PA-SR4-TX TX-A — host authoritative CREATION → registry + shadow session replay.
 *
 * Origin projection only — does not loop through signaling receive path.
 */
class Profile01HostLocalSessionFactProjection(
    private val readSignedCreationFact: (String) -> ByteArray?,
    private val readSignedSourceFact: (String) -> ByteArray?,
    private val ingress: Profile01ConferenceMediaFactIngress,
    private val supplementRegistry: Profile01SessionMediaSupplementRegistry,
    private val sessionIndex: MeetingProfile01ConferenceSessionIndex,
    private val registry: ConferenceSessionMediaControlFactRegistry,
    private val networkInterfaceName: () -> String,
) {
    fun maybeProjectHostShadowSession(sessionId: String): HostSessionProjectionOutcome {
        if (ConferenceSessionMediaBridge.hasSession(sessionId)) {
            Profile01ShadowRuntimeObservability.logHostSessionProjected(
                sessionId = sessionId,
                outcome = "ALREADY_LIVE",
            )
            return HostSessionProjectionOutcome.ALREADY_LIVE
        }

        val signedBytes =
            readSignedCreationFact(sessionId)
                ?: return defer(sessionId, "NO_CREATION")
        val binding =
            sessionIndex.bindingForSession(sessionId)
                ?: return defer(sessionId, "NO_SESSION_BINDING")
        val conferenceId =
            binding.conferenceId
                ?: sessionIndex.conferenceIdForSession(sessionId)
                ?: return defer(sessionId, "NO_CONFERENCE_ID")
        val mediaKeyEpoch =
            readCreationMediaKeyEpoch(signedBytes)
                ?: return defer(sessionId, "NO_MEDIA_KEY_EPOCH")
        val derived =
            supplementRegistry.lookup(conferenceId, mediaKeyEpoch)
                ?: return defer(sessionId, "NO_SUPPLEMENT")
        val supplement = derived.toSessionSupplement(binding.channelId)

        val result =
            ingress.ingestCreationSignedFact(
                signedFactBytes = signedBytes,
                supplement = supplement,
                networkInterfaceName = networkInterfaceName(),
            )
        if (result.decode !is Profile01SignedFactDecodeResult.Ready) {
            return defer(sessionId, "DECODE_REJECTED")
        }

        val publishOutcome = result.ingress?.publishOutcome
        if (publishOutcome != ControlFactPublishOutcome.ACCEPTED &&
            publishOutcome != ControlFactPublishOutcome.IDEMPOTENT
        ) {
            return defer(sessionId, publishOutcome?.name ?: "NOT_PUBLISHED")
        }

        val sessionControl =
            registry.session(conferenceId)
                ?: return defer(sessionId, "REGISTRY_ABSENT")
        Profile01ShadowSessionReplay.replaySessionStarted(
            sessionId = sessionId,
            channelId = binding.channelId,
            membershipVersionForRead = sessionControl.membershipVersion,
        )
        Profile01ShadowRuntimeObservability.logHostSessionProjected(
            sessionId = sessionId,
            outcome = "APPLIED",
            conferenceId = conferenceId,
            mediaKeyEpoch = sessionControl.mediaKeyEpoch,
        )
        return HostSessionProjectionOutcome.APPLIED
    }

    /**
     * Phase 1 — ingest membership + sync session registry to membership head (no wiring rotate).
     */
    fun prepareHostMembershipRegistry(
        sessionId: String,
        signedMembershipBytes: ByteArray,
    ): HostMembershipReplayOutcome = syncHostMembershipRegistry(sessionId, signedMembershipBytes)

    /**
     * Phase 2 — rotate/replay session wiring + reinstall authoritative member bindings at head.
     *
     * Must run after source declarations are published into registry (production ordering).
     */
    fun replayHostMembershipWiring(
        sessionId: String,
        localModuleId: String?,
    ): HostMembershipReplayOutcome {
        val binding =
            sessionIndex.bindingForSession(sessionId)
                ?: return deferMembershipReplay(sessionId, "NO_SESSION_BINDING")
        val conferenceId =
            binding.conferenceId
                ?: sessionIndex.conferenceIdForSession(sessionId)
                ?: return deferMembershipReplay(sessionId, "NO_CONFERENCE_ID")
        val sessionControl =
            registry.session(conferenceId)
                ?: return deferMembershipReplay(sessionId, "REGISTRY_ABSENT")
        sessionIndex.updateRosterEpoch(sessionId, sessionControl.membershipVersion)
        Profile01ShadowSessionReplay.replaySessionStarted(
            sessionId = sessionId,
            channelId = binding.channelId,
            membershipVersionForRead = sessionControl.membershipVersion,
        )
        projectHostLocalSourceMemberAtHead(sessionId, conferenceId)
        replayAuthoritativeMemberBindingsAtHead(
            sessionId = sessionId,
            conferenceId = conferenceId,
            localModuleId = localModuleId,
        )
        Profile01ShadowRuntimeObservability.logHostSessionProjected(
            sessionId = sessionId,
            outcome = "MEMBERSHIP_WIRING_REPLAY_APPLIED",
            conferenceId = conferenceId,
            mediaKeyEpoch = sessionControl.mediaKeyEpoch,
        )
        return HostMembershipReplayOutcome.APPLIED
    }

    /**
     * Host membership convergence — registry sync then wiring replay (test / legacy monolithic).
     *
     * Production host path uses [prepareHostMembershipRegistry] then source publish then
     * [replayHostMembershipWiring].
     */
    fun replayHostMembershipConvergence(
        sessionId: String,
        signedMembershipBytes: ByteArray,
        localModuleId: String? = null,
    ): HostMembershipReplayOutcome {
        when (prepareHostMembershipRegistry(sessionId, signedMembershipBytes)) {
            HostMembershipReplayOutcome.DEFERRED -> return HostMembershipReplayOutcome.DEFERRED
            HostMembershipReplayOutcome.APPLIED -> Unit
        }
        return replayHostMembershipWiring(sessionId, localModuleId)
    }

    private fun syncHostMembershipRegistry(
        sessionId: String,
        signedMembershipBytes: ByteArray,
    ): HostMembershipReplayOutcome {
        when (val membership = ingress.ingestMembershipSignedFact(signedMembershipBytes)) {
            is Profile01MembershipIngressResult.Converged -> Unit
            is Profile01MembershipIngressResult.Pending ->
                return deferMembershipReplay(sessionId, "PENDING_${membership.reason}")
            is Profile01MembershipIngressResult.Rejected ->
                return deferMembershipReplay(sessionId, "REJECTED_${membership.reason}")
        }
        val binding =
            sessionIndex.bindingForSession(sessionId)
                ?: return deferMembershipReplay(sessionId, "NO_SESSION_BINDING")
        val conferenceId =
            binding.conferenceId
                ?: sessionIndex.conferenceIdForSession(sessionId)
                ?: return deferMembershipReplay(sessionId, "NO_CONFERENCE_ID")
        when (
            ingress.syncSessionDeclarationToMembershipHead(
                conferenceId = conferenceId,
                channelId = binding.channelId,
                networkInterfaceName = networkInterfaceName(),
                creationSignedBytesForEndpoint = readSignedCreationFact(sessionId),
            )
        ) {
            SessionHeadSyncOutcome.APPLIED,
            SessionHeadSyncOutcome.ALREADY_SYNCED,
            -> Unit
            SessionHeadSyncOutcome.SKIPPED_INITIAL_MEMBERSHIP ->
                return deferMembershipReplay(sessionId, "SKIPPED_INITIAL_MEMBERSHIP")
            SessionHeadSyncOutcome.NO_MEMBERSHIP ->
                return deferMembershipReplay(sessionId, "NO_MEMBERSHIP")
            SessionHeadSyncOutcome.NO_SUPPLEMENT_REGISTRY,
            SessionHeadSyncOutcome.NO_SUPPLEMENT,
            -> return deferMembershipReplay(sessionId, "NO_SUPPLEMENT")
            SessionHeadSyncOutcome.NO_CREATION_CONTEXT ->
                return deferMembershipReplay(sessionId, "NO_CREATION_CONTEXT")
            SessionHeadSyncOutcome.REJECTED_INCOMPLETE,
            SessionHeadSyncOutcome.REJECTED_PUBLISH,
            -> return deferMembershipReplay(sessionId, "REGISTRY_SYNC_FAILED")
        }
        val sessionControl =
            registry.session(conferenceId)
                ?: return deferMembershipReplay(sessionId, "REGISTRY_ABSENT")
        Profile01ShadowRuntimeObservability.logHostSessionProjected(
            sessionId = sessionId,
            outcome = "MEMBERSHIP_REGISTRY_PREPARED",
            conferenceId = conferenceId,
            mediaKeyEpoch = sessionControl.mediaKeyEpoch,
        )
        return HostMembershipReplayOutcome.APPLIED
    }

    /**
     * Host-local SOURCE must reach the registry before member-ready replay, otherwise the local
     * binding is missing and the post-rotate catalog stays empty. Peer wire fanout publishes the
     * same signed fact outward but never populates our own registry.
     */
    private fun projectHostLocalSourceMemberAtHead(
        sessionId: String,
        conferenceId: String,
    ) {
        val outcome = maybeProjectHostLocalSourceMember(sessionId)
        Profile01ShadowRuntimeObservability.logHostSessionProjected(
            sessionId = sessionId,
            outcome = "LOCAL_SOURCE_REGISTRY_${outcome.name}",
            conferenceId = conferenceId,
            mediaKeyEpoch = registry.session(conferenceId)?.mediaKeyEpoch,
        )
    }

    /**
     * Completion first, member-ready second — same lifecycle the peer wire path drives.
     *
     * A successor incarnation leaves an unconsumed replace obligation in the registry; without
     * completion, member-ready stays `DEFERRED_REPLACE_PENDING` forever because no later event
     * re-drives it. Modules without an obligation no-op inside the completion seam.
     */
    private fun replayAuthoritativeMemberBindingsAtHead(
        sessionId: String,
        conferenceId: String,
        localModuleId: String?,
    ) {
        val moduleIds = linkedSetOf<String>()
        ingress.currentMembershipGeneration(conferenceId)?.members?.forEach { member ->
            moduleIds.add(member.moduleId)
        }
        registry.memberModuleIdsAtHead(conferenceId).forEach { moduleIds.add(it) }
        sessionIndex.bindingForSession(sessionId)?.mediaConnectedModules?.forEach { moduleIds.add(it) }
        localModuleId?.let { moduleIds.add(it) }
        for (moduleId in moduleIds.sorted()) {
            ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberReplaced(sessionId, moduleId)
            ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)
        }
    }

    /**
     * Host-local SOURCE → registry (authoritative bytes, not wire loopback).
     */
    fun maybeProjectHostLocalSourceMember(sessionId: String): HostSourceProjectionOutcome {
        val conferenceId =
            sessionIndex.conferenceIdForSession(sessionId)
                ?: return HostSourceProjectionOutcome.DEFERRED_NO_CONFERENCE
        val localModuleId = readLocalModuleIdFromSourceState(sessionId)
        if (localModuleId != null && registry.memberModuleIdsAtHead(conferenceId).contains(localModuleId)) {
            return HostSourceProjectionOutcome.ALREADY_PRESENT
        }
        val signedBytes =
            readSignedSourceFact(sessionId)
                ?: return HostSourceProjectionOutcome.DEFERRED_NO_SOURCE
        val result = ingress.ingestSourceDeclarationSignedFact(signedBytes)
        if (result.decode !is Profile01SignedFactDecodeResult.Ready) {
            return HostSourceProjectionOutcome.DEFERRED_DECODE
        }
        val publishOutcome = result.ingress?.publishOutcome
        if (publishOutcome != ControlFactPublishOutcome.ACCEPTED &&
            publishOutcome != ControlFactPublishOutcome.IDEMPOTENT
        ) {
            return HostSourceProjectionOutcome.DEFERRED_PUBLISH
        }
        return HostSourceProjectionOutcome.APPLIED
    }

    private fun deferMembershipReplay(
        sessionId: String,
        reason: String,
    ): HostMembershipReplayOutcome {
        Profile01ShadowRuntimeObservability.logHostSessionProjected(
            sessionId = sessionId,
            outcome = "MEMBERSHIP_REPLAY_DEFERRED_$reason",
        )
        return HostMembershipReplayOutcome.DEFERRED
    }

    private fun defer(
        sessionId: String,
        reason: String,
    ): HostSessionProjectionOutcome {
        Profile01ShadowRuntimeObservability.logHostSessionProjected(
            sessionId = sessionId,
            outcome = "DEFERRED_$reason",
        )
        return HostSessionProjectionOutcome.DEFERRED
    }

    private fun readLocalModuleIdFromSourceState(sessionId: String): String? {
        val signedBytes = readSignedSourceFact(sessionId) ?: return null
        return when (val decoded = Profile01WireCborDecoder.decodeSourceDeclarationMember(signedBytes)) {
            is Profile01WireCborDecoder.DecodeResult.Ready -> decoded.value.moduleId
            is Profile01WireCborDecoder.DecodeResult.Rejected -> null
        }
    }

    companion object {
        fun fromOriginBridges(
            creationOriginBridge: MeetingProfile01CreationOriginBridge,
            sourceOriginBridge: MeetingProfile01SourceOriginBridge,
            ingress: Profile01ConferenceMediaFactIngress,
            supplementRegistry: Profile01SessionMediaSupplementRegistry,
            sessionIndex: MeetingProfile01ConferenceSessionIndex,
            registry: ConferenceSessionMediaControlFactRegistry,
            networkInterfaceName: () -> String,
        ): Profile01HostLocalSessionFactProjection =
            Profile01HostLocalSessionFactProjection(
                readSignedCreationFact = creationOriginBridge::readSignedCreationFact,
                readSignedSourceFact = sourceOriginBridge::readSignedSourceFact,
                ingress = ingress,
                supplementRegistry = supplementRegistry,
                sessionIndex = sessionIndex,
                registry = registry,
                networkInterfaceName = networkInterfaceName,
            )

        internal fun readCreationMediaKeyEpoch(signedBytes: ByteArray): Long? =
            runCatching {
                val envelope = Profile01SignedFactEnvelope.parse(signedBytes) ?: return null
                val fullFact = Profile01CborCodec.decodeStrict(envelope.fullCanonicalBytes)
                val authority = fullFact.intKeyMap()?.get(2)?.intKeyMap()
                authority?.get(6)?.asUnsigned()
            }.getOrNull()
    }
}

enum class HostSessionProjectionOutcome {
    APPLIED,
    ALREADY_LIVE,
    DEFERRED,
}

enum class HostMembershipReplayOutcome {
    APPLIED,
    DEFERRED,
}

enum class HostSourceProjectionOutcome {
    APPLIED,
    ALREADY_PRESENT,
    DEFERRED_NO_CONFERENCE,
    DEFERRED_NO_SOURCE,
    DEFERRED_DECODE,
    DEFERRED_PUBLISH,
}
