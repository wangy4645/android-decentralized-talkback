package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.Profile01AuthoritativeMembershipChain
import com.talkback.core.conference.session.profile01.Profile01MembershipApplyResult
import com.talkback.core.conference.session.profile01.Profile01MembershipConvergenceRegistry
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
import com.talkback.core.conference.session.profile01.wire.Profile01MediaGroupDescriptor
import com.talkback.core.conference.session.profile01.wire.Profile01MembershipAuthoritySnapshot
import com.talkback.core.conference.session.profile01.wire.Profile01MembershipIncarnationAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants
import com.talkback.core.conference.session.profile01.wire.hexToId128Bytes
import com.talkback.core.conference.session.profile01.wire.toHexLower
import com.talkback.core.session.ConferenceTopologySnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative MEMBERSHIP origin bridge (RCA4).
 *
 * Trigger: authoritative conference roster changes after CREATION convergence —
 * not UI join attempts.
 */
class MeetingProfile01MembershipOriginBridge(
    private val sessionIndex: MeetingProfile01ConferenceSessionIndex,
    private val creationOriginBridge: MeetingProfile01CreationOriginBridge,
    private val mediaKeyAuthority: Profile01ConferenceMediaKeyMaterialAuthority,
    private val membershipConvergence: Profile01MembershipConvergenceRegistry,
    private val publisher: MeetingProfile01MembershipOriginPublisher?,
    private val hostLocalSupplementMaterializer: Profile01HostLocalMediaSupplementMaterializer? = null,
    private val onHostMembershipAuthoritativeCommit: ((sessionId: String) -> Unit)? = null,
    /** After MEMBERSHIP wire fanout — host may publish MEDIA_KEY_PACKAGE bound to the new head. */
    private val onMembershipWireFanoutComplete: ((sessionId: String) -> Unit)? = null,
    private val onLog: (String) -> Unit = {},
) {
    private data class MembershipPublicationState(
        val signedFactBytes: ByteArray,
        val membershipVersion: Long,
        val memberModuleIds: Set<String>,
        val publishedToPeers: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val membershipFactChain: MutableList<ByteArray> = mutableListOf(),
    )

    sealed class RejoinCatchUpReplayOutcome {
        data object Success : RejoinCatchUpReplayOutcome()

        data class Failed(val reason: String) : RejoinCatchUpReplayOutcome()
    }

    private val membershipStateBySession = ConcurrentHashMap<String, MembershipPublicationState>()

    fun onTopologyPublished(
        sessionId: String,
        snapshot: ConferenceTopologySnapshot,
        localModuleId: String,
        isMembershipAuthority: Boolean,
        eligiblePeerModuleIds: Set<String>,
        publishToPeer: (moduleId: String, signedFactBytes: ByteArray) -> Boolean,
    ): OriginEmitOutcome {
        if (!isMembershipAuthority) {
            logGap(sessionId, "NOT_MEMBERSHIP_AUTHORITY")
            return OriginEmitOutcome.SKIPPED_NOT_AUTHORITY
        }
        if (localModuleId != snapshot.hostModuleId) {
            logGap(sessionId, "NOT_HOST_OWNER")
            return OriginEmitOutcome.SKIPPED_NOT_OWNER
        }
        if (creationOriginBridge.readSignedCreationFact(sessionId) == null) {
            logGap(sessionId, "NO_CREATION")
            return OriginEmitOutcome.ORIGIN_SOURCE_GAP
        }
        val conferenceIdHex = sessionIndex.conferenceIdForSession(sessionId)
        if (conferenceIdHex == null) {
            logGap(sessionId, "NO_CONFERENCE_ID")
            return OriginEmitOutcome.ORIGIN_SOURCE_GAP
        }
        val converged = membershipConvergence.current(conferenceIdHex)
        if (converged == null) {
            logGap(sessionId, "MEMBERSHIP_NOT_CONVERGED")
            return OriginEmitOutcome.ORIGIN_SOURCE_GAP
        }

        val authoritativeMemberIds = snapshot.members.distinct().sorted()
        if (authoritativeMemberIds.isEmpty()) {
            logGap(sessionId, "EMPTY_ROSTER")
            return OriginEmitOutcome.ORIGIN_SOURCE_GAP
        }
        val convergedMemberIds = converged.members.map { it.moduleId }.distinct().sorted()
        val rosterAdvanced = authoritativeMemberIds != convergedMemberIds

        val createdThisCall =
            if (rosterAdvanced) {
                when (val created = createAndCacheMembershipFact(sessionId, snapshot, converged)) {
                    is MembershipFactResult.Created -> true
                    is MembershipFactResult.Gap -> return created.outcome
                    is MembershipFactResult.Unchanged -> false
                }
            } else {
                false
            }

        val state = membershipStateBySession[sessionId]
        if (state == null) {
            return if (rosterAdvanced) {
                OriginEmitOutcome.ORIGIN_SOURCE_GAP
            } else {
                OriginEmitOutcome.SKIPPED_NO_PENDING_PUBLICATION
            }
        }

        val channelId = sessionIndex.bindingForSession(sessionId)?.channelId ?: "-"
        val publishedCount =
            publishCommitChainToEligiblePeers(
                sessionId = sessionId,
                conferenceIdHex = conferenceIdHex,
                channelId = channelId,
                mediaKeyEpoch = membershipConvergence.current(conferenceIdHex)?.mediaKeyEpoch
                    ?: converged.mediaKeyEpoch,
                localModuleId = localModuleId,
                eligiblePeerModuleIds = eligiblePeerModuleIds,
                publishedToPeers = state.publishedToPeers,
                publishToPeer = publishToPeer,
            )

        // New head OR replay to peers that just became reachable — package after MEMBERSHIP wire.
        if (createdThisCall || publishedCount > 0) {
            onMembershipWireFanoutComplete?.invoke(sessionId)
        }

        return when {
            createdThisCall -> OriginEmitOutcome.EMITTED
            publishedCount > 0 -> OriginEmitOutcome.REPLAYED
            else -> OriginEmitOutcome.SKIPPED_NO_PENDING_PUBLICATION
        }
    }

    fun readSignedMembershipFact(sessionId: String): ByteArray? =
        membershipStateBySession[sessionId]?.signedFactBytes?.copyOf()

    fun clearSession(sessionId: String) {
        membershipStateBySession.remove(sessionId)
    }

    fun replayAuthoritativeMembershipChainToPeer(
        sessionId: String,
        conferenceIdHex: String,
        peerModuleId: String,
        creationSignedFactBytes: ByteArray,
        publishToPeer: (moduleId: String, signedFactBytes: ByteArray) -> Boolean,
        reason: Profile01CommitResyncReason = Profile01CommitResyncReason.REJOIN,
    ): RejoinCatchUpReplayOutcome {
        val head =
            membershipConvergence.current(conferenceIdHex)
                ?: return RejoinCatchUpReplayOutcome.Failed("MEMBERSHIP_NOT_CONVERGED")
        val state = membershipStateBySession[sessionId]
        val chainBytes = state?.membershipFactChain?.map { it.copyOf() } ?: emptyList()
        when (
            val validation =
                Profile01AuthoritativeMembershipChain.validateAgainstCreationAndHead(
                    creationSignedFactBytes = creationSignedFactBytes,
                    chainSignedFacts = chainBytes,
                    expectedHead = head,
                )
        ) {
            is Profile01AuthoritativeMembershipChain.Validation.Invalid ->
                return RejoinCatchUpReplayOutcome.Failed(validation.reason)
            is Profile01AuthoritativeMembershipChain.Validation.Valid -> {
                state?.publishedToPeers?.remove(peerModuleId)
                validation.links.forEach { link ->
                    if (!publishToPeer(peerModuleId, link.signedFactBytes)) {
                        return RejoinCatchUpReplayOutcome.Failed(
                            "MEMBERSHIP_SEND_FAILED_V${link.membershipVersion}",
                        )
                    }
                    onLog(
                        "PROFILE01_COMMIT_BUNDLE session=$sessionId reason=${reason.name} " +
                            "factType=${Profile01WireConstants.FACT_TYPE_MEMBERSHIP} " +
                            "target=$peerModuleId membershipVersion=${link.membershipVersion}",
                    )
                    if (reason == Profile01CommitResyncReason.REJOIN) {
                        onLog(
                            "PROFILE01_REJOIN_CATCHUP session=$sessionId factType=" +
                                "${Profile01WireConstants.FACT_TYPE_MEMBERSHIP} target=$peerModuleId " +
                                "membershipVersion=${link.membershipVersion}",
                        )
                    }
                }
                return RejoinCatchUpReplayOutcome.Success
            }
        }
    }

    private sealed interface MembershipFactResult {
        data object Created : MembershipFactResult

        data object Unchanged : MembershipFactResult

        data class Gap(val outcome: OriginEmitOutcome) : MembershipFactResult
    }

    private fun createAndCacheMembershipFact(
        sessionId: String,
        snapshot: ConferenceTopologySnapshot,
        converged: com.talkback.core.conference.session.profile01.Profile01MembershipGeneration,
    ): MembershipFactResult {
        val publisher = publisher
        if (publisher == null) {
            logGap(sessionId, "NO_SIGNER")
            return MembershipFactResult.Gap(OriginEmitOutcome.ORIGIN_SOURCE_GAP)
        }

        val authoritativeMemberIds = snapshot.members.distinct().sorted()
        val convergedMemberIds = converged.members.map { it.moduleId }.distinct().sorted()
        if (authoritativeMemberIds == convergedMemberIds) {
            return MembershipFactResult.Unchanged
        }

        val conferenceIdHex = sessionIndex.conferenceIdForSession(sessionId)!!
        val conferenceId = conferenceIdHex.hexToId128Bytes()
        val membershipView =
            authoritativeMemberIds.map { moduleId ->
                Profile01WireMembershipMember(
                    moduleId = moduleId,
                    membershipIncarnationId =
                        Profile01MembershipIncarnationAuthority.deriveIncarnationId128(
                            conferenceId = conferenceId,
                            moduleId = moduleId,
                        ),
                )
            }
        val descriptorDigest =
            Profile01FactDigest.descriptorDigest(Profile01MediaGroupDescriptor.productionDescriptor())
        val membershipVersion = converged.membershipVersion + 1L
        val mediaKeyEpoch = converged.mediaKeyEpoch + 1L
        val previousMembershipDigest = converged.generationFactDigest.copyOf()
        val conferenceEpoch = snapshot.meshGeneration.coerceAtLeast(converged.conferenceEpoch)

        val material =
            mediaKeyAuthority.rotateForMembership(
                sessionId = sessionId,
                conferenceId = conferenceId,
                conferenceEpoch = conferenceEpoch,
                ownerModuleId = snapshot.hostModuleId,
                mediaGroupDescriptorDigest = descriptorDigest,
                membershipVersion = membershipVersion,
                previousMembershipDigest = previousMembershipDigest,
                membershipView = membershipView,
                mediaKeyEpoch = mediaKeyEpoch,
            )

        val authoritySnapshot =
            Profile01MembershipAuthoritySnapshot(
                conferenceId = conferenceId.copyOf(),
                conferenceEpoch = conferenceEpoch,
                ownerModuleId = snapshot.hostModuleId,
                membershipVersion = membershipVersion,
                previousMembershipDigest = previousMembershipDigest,
                members = membershipView,
                mediaKeyEpoch = mediaKeyEpoch,
                mediaKeyCommitment = material.mediaKeyCommitment.copyOf(),
                signerKeyVersion = publisher.signerKeyVersion,
            )
        val signedFactBytes = publisher.publishMembership(authoritySnapshot)
        val digestHex =
            Profile01SignedFactEnvelope.parse(signedFactBytes)?.let { envelope ->
                Profile01FactDigest.computeFactDigest(envelope.fullCanonicalBytes).toHexLower()
            } ?: "unknown"

        val priorChain =
            membershipStateBySession[sessionId]?.membershipFactChain?.map { it.copyOf() }
                ?: emptyList()
        val nextChain = priorChain.toMutableList()
        nextChain.add(signedFactBytes.copyOf())
        membershipStateBySession[sessionId] =
            MembershipPublicationState(
                signedFactBytes = signedFactBytes.copyOf(),
                membershipVersion = membershipVersion,
                memberModuleIds = authoritativeMemberIds.toSet(),
                membershipFactChain = nextChain,
            )
        sessionIndex.updateRosterEpoch(sessionId, membershipVersion)

        onLog(
            "PROFILE01_ORIGIN_EMIT session=$sessionId factType=${Profile01WireConstants.FACT_TYPE_MEMBERSHIP} " +
                "outcome=EMITTED conferenceId=$conferenceIdHex factDigest=$digestHex " +
                "members=${authoritativeMemberIds.size} membershipVersion=$membershipVersion " +
                "mediaKeyEpoch=$mediaKeyEpoch",
        )

        hostLocalSupplementMaterializer?.materializeFromAuthority(
            HostLocalSupplementMaterializationRequest(
                sessionId = sessionId,
                conferenceIdHex = conferenceIdHex,
                conferenceEpoch = conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                expectedMediaKeyCommitment = material.mediaKeyCommitment.copyOf(),
            ),
        )
        projectHostMembershipGeneration(signedFactBytes)
        onHostMembershipAuthoritativeCommit?.invoke(sessionId)
        return MembershipFactResult.Created
    }

    private fun projectHostMembershipGeneration(
        signedFactBytes: ByteArray,
    ) {
        when (val decoded = Profile01WireCborDecoder.decodeMembership(signedFactBytes)) {
            is Profile01WireCborDecoder.DecodeResult.Rejected -> return
            is Profile01WireCborDecoder.DecodeResult.Ready ->
                when (membershipConvergence.applyMembership(decoded.value)) {
                    is Profile01MembershipApplyResult.Accepted,
                    is Profile01MembershipApplyResult.Idempotent,
                    -> Unit
                    else -> Unit
                }
        }
    }

    /**
     * Steady-state membership fanout is the commit bundle chain, not only the
     * latest MEMBERSHIP fact. Late joiners must receive predecessors or they
     * stall at CREATION head ([PREDECESSOR_NOT_CURRENT_HEAD]).
     */
    private fun publishCommitChainToEligiblePeers(
        sessionId: String,
        conferenceIdHex: String,
        channelId: String,
        mediaKeyEpoch: Long,
        localModuleId: String,
        eligiblePeerModuleIds: Set<String>,
        publishedToPeers: MutableSet<String>,
        publishToPeer: (moduleId: String, signedFactBytes: ByteArray) -> Boolean,
    ): Int {
        val creationBytes = creationOriginBridge.readSignedCreationFact(sessionId) ?: return 0
        var publishedCount = 0
        eligiblePeerModuleIds
            .filter { it != localModuleId }
            .sorted()
            .forEach { peerModuleId ->
                if (!publishedToPeers.add(peerModuleId)) {
                    Profile01ControlPlaneDiagnosticTrace.recordMembershipOriginPeer(
                        sessionId = sessionId,
                        conferenceIdHex = conferenceIdHex,
                        channelId = channelId,
                        mediaKeyEpoch = mediaKeyEpoch,
                        peerModuleId = peerModuleId,
                        disposition =
                            Profile01ControlPlaneDiagnosticTrace.MembershipOriginPeerDisposition
                                .SKIPPED_ALREADY_PUBLISHED,
                    )
                    return@forEach
                }
                when (
                    val replay =
                        replayAuthoritativeMembershipChainToPeer(
                            sessionId = sessionId,
                            conferenceIdHex = conferenceIdHex,
                            peerModuleId = peerModuleId,
                            creationSignedFactBytes = creationBytes,
                            publishToPeer = publishToPeer,
                            reason = Profile01CommitResyncReason.LATE_JOIN,
                        )
                ) {
                    is RejoinCatchUpReplayOutcome.Success -> {
                        publishedToPeers.add(peerModuleId)
                        publishedCount++
                        Profile01ControlPlaneDiagnosticTrace.recordMembershipOriginPeer(
                            sessionId = sessionId,
                            conferenceIdHex = conferenceIdHex,
                            channelId = channelId,
                            mediaKeyEpoch = mediaKeyEpoch,
                            peerModuleId = peerModuleId,
                            disposition =
                                Profile01ControlPlaneDiagnosticTrace.MembershipOriginPeerDisposition.SENT,
                        )
                        onLog(
                            "PROFILE01_ORIGIN_REPLAY session=$sessionId factType=" +
                                "${Profile01WireConstants.FACT_TYPE_MEMBERSHIP} target=$peerModuleId",
                        )
                    }
                    is RejoinCatchUpReplayOutcome.Failed -> {
                        publishedToPeers.remove(peerModuleId)
                        Profile01ControlPlaneDiagnosticTrace.recordMembershipOriginPeer(
                            sessionId = sessionId,
                            conferenceIdHex = conferenceIdHex,
                            channelId = channelId,
                            mediaKeyEpoch = mediaKeyEpoch,
                            peerModuleId = peerModuleId,
                            disposition =
                                Profile01ControlPlaneDiagnosticTrace.MembershipOriginPeerDisposition.SEND_FAILED,
                        )
                        onLog(
                            "PROFILE01_COMMIT_BUNDLE session=$sessionId reason=LATE_JOIN " +
                                "outcome=FAILED peer=$peerModuleId detail=${replay.reason}",
                        )
                    }
                }
            }
        return publishedCount
    }

    private fun logGap(sessionId: String, reason: String) {
        onLog(
            "PROFILE01_ORIGIN_EMIT session=$sessionId factType=${Profile01WireConstants.FACT_TYPE_MEMBERSHIP} " +
                "outcome=ORIGIN_SOURCE_GAP reason=$reason",
        )
    }
}
