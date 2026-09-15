package com.talkback.core.conference.session.integration

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
    private val onLog: (String) -> Unit = {},
) {
    private data class MembershipPublicationState(
        val signedFactBytes: ByteArray,
        val membershipVersion: Long,
        val memberModuleIds: Set<String>,
        val publishedToPeers: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    )

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

        val publishedCount =
            publishToEligiblePeers(
                sessionId = sessionId,
                localModuleId = localModuleId,
                eligiblePeerModuleIds = eligiblePeerModuleIds,
                signedFactBytes = state.signedFactBytes,
                publishedToPeers = state.publishedToPeers,
                publishToPeer = publishToPeer,
                replayLog = createdThisCall,
            )

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

        membershipStateBySession[sessionId] =
            MembershipPublicationState(
                signedFactBytes = signedFactBytes.copyOf(),
                membershipVersion = membershipVersion,
                memberModuleIds = authoritativeMemberIds.toSet(),
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

    private fun publishToEligiblePeers(
        sessionId: String,
        localModuleId: String,
        eligiblePeerModuleIds: Set<String>,
        signedFactBytes: ByteArray,
        publishedToPeers: MutableSet<String>,
        publishToPeer: (moduleId: String, signedFactBytes: ByteArray) -> Boolean,
        replayLog: Boolean,
    ): Int {
        var publishedCount = 0
        eligiblePeerModuleIds
            .filter { it != localModuleId }
            .sorted()
            .forEach { peerModuleId ->
                if (!publishedToPeers.add(peerModuleId)) return@forEach
                if (publishToPeer(peerModuleId, signedFactBytes)) {
                    publishedCount++
                    if (!replayLog) {
                        onLog(
                            "PROFILE01_ORIGIN_REPLAY session=$sessionId factType=" +
                                "${Profile01WireConstants.FACT_TYPE_MEMBERSHIP} target=$peerModuleId",
                        )
                    }
                } else {
                    publishedToPeers.remove(peerModuleId)
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
