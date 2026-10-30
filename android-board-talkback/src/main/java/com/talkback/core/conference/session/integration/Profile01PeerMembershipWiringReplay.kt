package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.SessionHeadSyncOutcome
import com.talkback.core.conference.session.integration.cutover.MulticastAudibleAutomaticCutover

/**
 * Peer post-membership shadow replay — registry session sync, mediaKey epoch rotation,
 * authoritative member bindings, then local SOURCE rebuild + TX binding reinstall.
 */
class Profile01PeerMembershipWiringReplay(
    private val ingress: Profile01ConferenceMediaFactIngress,
    private val sessionIndex: MeetingProfile01ConferenceSessionIndex,
    private val registry: ConferenceSessionMediaControlFactRegistry,
    private val networkInterfaceName: () -> String,
    private val localModuleId: () -> String,
    private val creationSignedBytesForSession: (sessionId: String) -> ByteArray?,
    private val onLocalMembershipHeadConsumptionReady: (sessionId: String) -> Unit,
    private val memberBindingMaterializer: Profile01ShadowMemberBindingMaterializer?,
) {
    fun replayAfterMembershipWireApplied(sessionId: String): PeerMembershipWiringReplayOutcome {
        val binding =
            sessionIndex.bindingForSession(sessionId)
                ?: return defer(sessionId, PeerMembershipWiringReplayOutcome.DEFERRED_NO_SESSION)
        val conferenceId =
            binding.conferenceId
                ?: sessionIndex.conferenceIdForSession(sessionId)
                ?: return defer(sessionId, PeerMembershipWiringReplayOutcome.DEFERRED_NO_CONFERENCE)
        val generation =
            ingress.currentMembershipGeneration(conferenceId)
                ?: return defer(sessionId, PeerMembershipWiringReplayOutcome.DEFERRED_NO_MEMBERSHIP, conferenceId)
        if (generation.membershipVersion == 0L) {
            return defer(
                sessionId,
                PeerMembershipWiringReplayOutcome.DEFERRED_INITIAL_MEMBERSHIP,
                conferenceId,
            )
        }
        when (
            ingress.syncSessionDeclarationToMembershipHead(
                conferenceId = conferenceId,
                channelId = binding.channelId,
                networkInterfaceName = networkInterfaceName(),
                creationSignedBytesForEndpoint = creationSignedBytesForSession(sessionId),
            )
        ) {
            SessionHeadSyncOutcome.APPLIED,
            SessionHeadSyncOutcome.ALREADY_SYNCED,
            -> Unit
            SessionHeadSyncOutcome.NO_SUPPLEMENT,
            SessionHeadSyncOutcome.NO_SUPPLEMENT_REGISTRY,
            -> return defer(
                sessionId,
                PeerMembershipWiringReplayOutcome.DEFERRED_NO_SUPPLEMENT,
                conferenceId,
                generation.mediaKeyEpoch,
            )
            SessionHeadSyncOutcome.SKIPPED_INITIAL_MEMBERSHIP ->
                return defer(
                    sessionId,
                    PeerMembershipWiringReplayOutcome.DEFERRED_INITIAL_MEMBERSHIP,
                    conferenceId,
                )
            SessionHeadSyncOutcome.NO_MEMBERSHIP ->
                return defer(sessionId, PeerMembershipWiringReplayOutcome.DEFERRED_NO_MEMBERSHIP, conferenceId)
            SessionHeadSyncOutcome.NO_CREATION_CONTEXT ->
                return defer(
                    sessionId,
                    PeerMembershipWiringReplayOutcome.DEFERRED_NO_CREATION_CONTEXT,
                    conferenceId,
                    generation.mediaKeyEpoch,
                )
            SessionHeadSyncOutcome.REJECTED_INCOMPLETE,
            SessionHeadSyncOutcome.REJECTED_PUBLISH,
            -> return defer(
                sessionId,
                PeerMembershipWiringReplayOutcome.DEFERRED_REGISTRY_SYNC,
                conferenceId,
                generation.mediaKeyEpoch,
            )
        }

        val sessionControl =
            registry.session(conferenceId)
                ?: return defer(
                    sessionId,
                    PeerMembershipWiringReplayOutcome.DEFERRED_REGISTRY_SYNC,
                    conferenceId,
                    generation.mediaKeyEpoch,
                )

        sessionIndex.updateRosterEpoch(sessionId, sessionControl.membershipVersion)

        Profile01ShadowSessionReplay.replaySessionStarted(
            sessionId = sessionId,
            channelId = binding.channelId,
            membershipVersionForRead = sessionControl.membershipVersion,
        )

        onLocalMembershipHeadConsumptionReady(sessionId)

        replayAuthoritativeMemberBindingsAtHead(
            sessionId = sessionId,
            conferenceId = conferenceId,
            localModuleId = localModuleId(),
        )
        memberBindingMaterializer?.materializeLocalIfReady(sessionId)
        logCatalogExpectation(
            sessionId = sessionId,
            conferenceId = conferenceId,
            mediaKeyEpoch = sessionControl.mediaKeyEpoch,
            replayOutcome = "APPLIED",
        )
        logCommitReadiness(sessionId, conferenceId)

        Profile01ShadowRuntimeObservability.logHostSessionProjected(
            sessionId = sessionId,
            outcome = "PEER_MEMBERSHIP_WIRING_REPLAY_APPLIED",
            conferenceId = conferenceId,
            mediaKeyEpoch = sessionControl.mediaKeyEpoch,
        )

        MulticastAudibleAutomaticCutover.maybeAttempt(sessionId, localModuleId())
        return PeerMembershipWiringReplayOutcome.APPLIED
    }

    fun replayAfterMediaKeyAppliedIfMembershipReady(sessionId: String) {
        replayAfterMembershipWireApplied(sessionId)
    }

    /**
     * Re-drive [ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady] for
     * registry bindings at the current session mediaKeyEpoch only — after wiring rotation
     * has cleared the multicast catalog.
     */
    fun replayMemberBindingsAfterMediaKeyEpochRotated(sessionId: String) {
        if (ConferenceSessionMediaBridge.wiring == null) return
        val binding = sessionIndex.bindingForSession(sessionId) ?: return
        val conferenceId =
            binding.conferenceId
                ?: sessionIndex.conferenceIdForSession(sessionId)
                ?: return
        val sessionControl = registry.session(conferenceId) ?: return
        val wiringEpoch = ConferenceSessionMediaBridge.currentMediaKeyEpoch(sessionId) ?: return
        if (wiringEpoch != sessionControl.mediaKeyEpoch) return

        replayAuthoritativeMemberBindingsAtHead(
            sessionId = sessionId,
            conferenceId = conferenceId,
            localModuleId = localModuleId(),
        )
        memberBindingMaterializer?.materializeLocalIfReady(sessionId)
        logCatalogExpectation(
            sessionId = sessionId,
            conferenceId = conferenceId,
            mediaKeyEpoch = sessionControl.mediaKeyEpoch,
            replayOutcome = "POST_EPOCH_REPLAY",
        )
        logCommitReadiness(sessionId, conferenceId)
        MulticastAudibleAutomaticCutover.maybeAttempt(sessionId, localModuleId())
    }

    private fun defer(
        sessionId: String,
        outcome: PeerMembershipWiringReplayOutcome,
        conferenceId: String? = null,
        mediaKeyEpoch: Long? = null,
    ): PeerMembershipWiringReplayOutcome {
        Profile01ShadowRuntimeObservability.logHostSessionProjected(
            sessionId = sessionId,
            outcome = "PEER_MEMBERSHIP_WIRING_REPLAY_DEFERRED_${outcome.name.removePrefix("DEFERRED_")}",
            conferenceId = conferenceId,
            mediaKeyEpoch = mediaKeyEpoch,
        )
        return outcome
    }

    private fun logCommitReadiness(
        sessionId: String,
        conferenceId: String,
    ) {
        val generation = ingress.currentMembershipGeneration(conferenceId) ?: return
        val commit = ConferenceMediaCommit.from(generation, sessionId)
        val expectedModuleIds =
            ConferenceSessionMediaBridge.wiring?.expectedCatalog(sessionId)?.moduleIds()
                ?: emptySet()
        val bound =
            ConferenceSessionMediaBridge.wiring?.catalog(sessionId)?.all()?.map { it.moduleId }?.toSet()
                ?: emptySet()
        val result =
            Profile01CommitReadiness.evaluate(
                commit = commit,
                localModuleId = localModuleId(),
                localHeadDigest = Profile01HeadDigest.fromGeneration(generation),
                currentHeadSupplementInstalled =
                    ConferenceSessionMediaBridge.currentMediaKeyEpoch(sessionId) == commit.mediaKeyEpoch,
                expectedModuleIds = expectedModuleIds,
                boundCatalogModuleIds = bound,
            )
        Profile01ShadowRuntimeObservability.logPhase(
            phase = "COMMIT_READY",
            sessionId = sessionId,
            conferenceId = conferenceId,
            mediaKeyEpoch = commit.mediaKeyEpoch,
            fields =
                mapOf(
                    "ready" to result.ready.toString(),
                    "headDigest" to result.headDigest,
                    "missing" to result.missing.joinToString(",").ifEmpty { "none" },
                    "expectedMembers" to result.expectedMembers.sorted().joinToString(","),
                    "boundCatalog" to bound.sorted().joinToString(",").ifEmpty { "none" },
                ),
        )
    }

    /**
     * Expected set is the committed membership snapshot. SOURCE/registry facts only
     * bind wiring; they do not define who must be present.
     */
    private fun logCatalogExpectation(
        sessionId: String,
        conferenceId: String,
        mediaKeyEpoch: Long,
        replayOutcome: String,
    ) {
        val expected = ConferenceSessionMediaBridge.wiring?.expectedCatalog(sessionId)
        val snap = ConferenceSessionMediaBridge.wiring?.runtimeSnapshot(sessionId)
        val generation = ingress.currentMembershipGeneration(conferenceId)
        val commit = generation?.let { ConferenceMediaCommit.from(it, sessionId) }
        Profile01ShadowRuntimeObservability.logPhase(
            phase = "CATALOG_L1B",
            sessionId = sessionId,
            conferenceId = conferenceId,
            mediaKeyEpoch = mediaKeyEpoch,
            fields =
                mapOf(
                    "replayOutcome" to replayOutcome,
                    "catalogEntries" to (snap?.catalogEntries ?: 0).toString(),
                    "expectedMembers" to (snap?.expectedMembers ?: expected?.size() ?: 0).toString(),
                    "pendingSourceMembers" to
                        (snap?.pendingSourceMembers ?: expected?.pendingCount() ?: 0).toString(),
                    "pendingModuleIds" to
                        (expected?.pendingModuleIds()?.joinToString(",") ?: "none").ifEmpty { "none" },
                    "commitHeadDigest" to (commit?.headDigest ?: "none"),
                    "commitMembers" to
                        (commit?.members?.sorted()?.joinToString(",") ?: "none"),
                ),
        )
    }

    private fun replayAuthoritativeMemberBindingsAtHead(
        sessionId: String,
        conferenceId: String,
        localModuleId: String,
    ) {
        val generation = ingress.currentMembershipGeneration(conferenceId)
        val present = linkedSetOf<String>()
        if (generation != null) {
            generation.members.forEach { present.add(it.moduleId) }
        }
        present.add(localModuleId)
        ConferenceSessionMediaBridge.expectPresentMembers(
            sessionId,
            present,
            pendingReason = "MEMBERSHIP_COMMIT",
        )
        ConferenceSessionMediaBridge.pruneCatalogOutsideCommit(sessionId, present)

        val moduleIds = linkedSetOf<String>()
        registry.memberModuleIdsAtHead(conferenceId).forEach { id ->
            if (id in present) moduleIds.add(id)
        }
        moduleIds.add(localModuleId)

        for (moduleId in moduleIds.sorted()) {
            ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberReplaced(sessionId, moduleId)
            ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)
            if (moduleId == localModuleId) {
                Profile01ShadowRuntimeObservability.logShadowTxBindingInstalled(sessionId, moduleId)
            }
        }
    }
}

enum class PeerMembershipWiringReplayOutcome {
    APPLIED,
    DEFERRED_NO_SESSION,
    DEFERRED_NO_CONFERENCE,
    DEFERRED_NO_MEMBERSHIP,
    DEFERRED_INITIAL_MEMBERSHIP,
    DEFERRED_NO_SUPPLEMENT,
    DEFERRED_NO_CREATION_CONTEXT,
    DEFERRED_REGISTRY_SYNC,
}
