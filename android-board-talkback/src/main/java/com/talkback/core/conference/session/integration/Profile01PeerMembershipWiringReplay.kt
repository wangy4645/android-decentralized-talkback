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

    /**
     * Only modules with a registry member fact coherent at session head (matching mediaKeyEpoch).
     * Roster-only ids without a current-epoch SOURCE are not driven — they wait on wire/materializer.
     */
    private fun replayAuthoritativeMemberBindingsAtHead(
        sessionId: String,
        conferenceId: String,
        localModuleId: String,
    ) {
        val moduleIds = linkedSetOf<String>()
        registry.memberModuleIdsAtHead(conferenceId).forEach { moduleIds.add(it) }
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
