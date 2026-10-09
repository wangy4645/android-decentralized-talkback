package com.talkback.core.conference.session.integration



import com.talkback.core.conference.session.profile01.Profile01MembershipConvergenceRegistry
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceIdAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder

import com.talkback.core.conference.session.profile01.wire.Profile01CreationAuthoritySnapshot

import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority

import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest

import com.talkback.core.conference.session.profile01.wire.Profile01MediaGroupDescriptor

import com.talkback.core.conference.session.profile01.wire.Profile01MembershipIncarnationAuthority

import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactSignerResolveResult
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactSignerSource

import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants

import com.talkback.core.conference.session.profile01.wire.hexToId128Bytes

import com.talkback.core.conference.session.profile01.wire.toHexLower

import com.talkback.core.session.ConferenceTopologySnapshot

import java.util.concurrent.ConcurrentHashMap



/**

 * Authoritative CREATION origin bridge (Step 1 + per-peer publication replay).

 *

 * Trigger: [ConferenceTopologyAuthority.PublishResult.Published] and eligible-peer

 * admission — not UI create.

 *

 * The signed CREATION fact is created once per session; per-peer publication is tracked

 * independently so late-joining remote peers receive the same cached envelope.

 */

class MeetingProfile01CreationOriginBridge(

    private val sessionIndex: MeetingProfile01ConferenceSessionIndex,

    private val mediaKeyAuthority: Profile01ConferenceMediaKeyMaterialAuthority,

    private val signerSource: Profile01SignedFactSignerSource,

    private val membershipConvergence: Profile01MembershipConvergenceRegistry? = null,

    private val onHostCreationAuthoritativeCommit: ((sessionId: String) -> Unit)? = null,

    private val hostLocalSupplementMaterializer: Profile01HostLocalMediaSupplementMaterializer? = null,

    private val onLog: (String) -> Unit = {},

) {

    private data class CreationPublicationState(

        val signedFactBytes: ByteArray,

        val publishedToPeers: MutableSet<String> = ConcurrentHashMap.newKeySet(),

    )



    private val creationStateBySession = ConcurrentHashMap<String, CreationPublicationState>()



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

        val existingState = creationStateBySession[sessionId]

        val createdThisCall =

            if (existingState != null) {

                false

            } else {

                when (val created = createAndCacheCreationFact(sessionId, snapshot)) {

                    is CreationFactResult.Created -> true

                    is CreationFactResult.Gap -> return created.outcome

                }

            }

        val state = creationStateBySession[sessionId] ?: return OriginEmitOutcome.ORIGIN_SOURCE_GAP

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



    /**
     * Read-only CREATION canonical bytes for downstream origin bridges (P1-C).
     * Does not mutate [CreationPublicationState.publishedToPeers].
     */
    fun readSignedCreationFact(sessionId: String): ByteArray? =
        creationStateBySession[sessionId]?.signedFactBytes?.copyOf()

    fun clearSession(sessionId: String) {

        creationStateBySession.remove(sessionId)

        mediaKeyAuthority.clearSession(sessionId)

    }



    private sealed interface CreationFactResult {

        data object Created : CreationFactResult



        data class Gap(val outcome: OriginEmitOutcome) : CreationFactResult

    }



    private fun createAndCacheCreationFact(

        sessionId: String,

        snapshot: ConferenceTopologySnapshot,

    ): CreationFactResult {

        val publisher =
            when (val resolved = signerSource.resolve()) {
                is Profile01SignedFactSignerResolveResult.Ready ->
                    MeetingProfile01CreationOriginPublisher(resolved.signer)
                is Profile01SignedFactSignerResolveResult.Unavailable -> {
                    logGap(sessionId, resolved.reason)
                    return CreationFactResult.Gap(OriginEmitOutcome.ORIGIN_SOURCE_GAP)
                }
            }

        val members = snapshot.members.distinct().sorted()

        if (members.isEmpty()) {

            logGap(sessionId, "EMPTY_ROSTER")

            return CreationFactResult.Gap(OriginEmitOutcome.ORIGIN_SOURCE_GAP)

        }

        val conferenceIdHex = sessionIndex.ensureConferenceIdHex(sessionId)

        val conferenceId = conferenceIdHex.hexToId128Bytes()

        val membershipView =

            members.map { moduleId ->

                Profile01WireMembershipMember(

                    moduleId = moduleId,

                    membershipIncarnationId =

                        Profile01MembershipIncarnationAuthority.deriveIncarnationId128(

                            conferenceId = conferenceId,

                            moduleId = moduleId,

                        ),

                )

            }

        val descriptor = Profile01MediaGroupDescriptor.productionDescriptor()

        val descriptorDigest = Profile01FactDigest.descriptorDigest(descriptor)

        val membershipVersion = 0L

        val mediaKeyEpoch = 1L

        val conferenceEpoch = snapshot.meshGeneration.coerceAtLeast(1L)

        val material =

            mediaKeyAuthority.ensureMaterial(

                sessionId = sessionId,

                conferenceId = conferenceId,

                conferenceEpoch = conferenceEpoch,

                ownerModuleId = snapshot.hostModuleId,

                mediaGroupDescriptorDigest = descriptorDigest,

                membershipView = membershipView,

                membershipVersion = membershipVersion,

                mediaKeyEpoch = mediaKeyEpoch,

            )

        val authoritySnapshot =

            Profile01CreationAuthoritySnapshot(

                conferenceId = conferenceId.copyOf(),

                conferenceEpoch = conferenceEpoch,

                ownerModuleId = snapshot.hostModuleId,

                mediaGroupDescriptor = descriptor,

                membershipView = membershipView,

                initialMembershipVersion = membershipVersion,

                initialMediaKeyEpoch = mediaKeyEpoch,

                mediaKeyCommitment = material.mediaKeyCommitment.copyOf(),

                signerKeyVersion = publisher.signerKeyVersion,

            )

        val signedFactBytes = publisher.publishCreation(authoritySnapshot)

        sessionIndex.bindConferenceId(sessionId, conferenceIdHex)

        creationStateBySession[sessionId] =

            CreationPublicationState(signedFactBytes = signedFactBytes.copyOf())

        val digest =

            Profile01SignedFactEnvelope.parse(signedFactBytes)?.let { envelope ->

                Profile01FactDigest.computeFactDigest(envelope.fullCanonicalBytes).toHexLower()

            } ?: "unknown"

        onLog(

            "PROFILE01_ORIGIN_EMIT session=$sessionId factType=${Profile01WireConstants.FACT_TYPE_CREATION} " +

                "outcome=EMITTED conferenceId=$conferenceIdHex factDigest=$digest " +

                "members=${members.size} meshGen=${snapshot.meshGeneration}",

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

        projectHostMembershipEligibility(sessionId, signedFactBytes)

        onHostCreationAuthoritativeCommit?.invoke(sessionId)

        return CreationFactResult.Created

    }

    /**
     * PR-PA-SR-B1-R1 R1-A: project authoritative committed CREATION into local membership convergence.
     * Runs only after [creationStateBySession] and [MeetingProfile01ConferenceSessionIndex.bindConferenceId].
     */
    private fun projectHostMembershipEligibility(
        sessionId: String,
        signedFactBytes: ByteArray,
    ) {
        val convergence = membershipConvergence ?: return
        if (creationStateBySession[sessionId] == null) return
        if (sessionIndex.conferenceIdForSession(sessionId) == null) return
        val binding = sessionIndex.bindingForSession(sessionId) ?: return
        val supplement =
            Profile01SessionMediaSupplement(
                channelId = binding.channelId,
                masterKey = ByteArray(16),
                masterSalt = ByteArray(12),
                keyContextHint64 = ByteArray(8),
            )
        when (
            val decoded =
                Profile01WireCborDecoder.decodeCreationSession(signedFactBytes, supplement)
        ) {
            is Profile01WireCborDecoder.DecodeResult.Rejected -> return
            is Profile01WireCborDecoder.DecodeResult.Ready ->
                convergence.seedFromCreation(decoded.value)
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

                                "${Profile01WireConstants.FACT_TYPE_CREATION} target=$peerModuleId",

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

            "PROFILE01_ORIGIN_EMIT session=$sessionId factType=${Profile01WireConstants.FACT_TYPE_CREATION} " +

                "outcome=ORIGIN_SOURCE_GAP reason=$reason",

        )

    }

}



enum class OriginEmitOutcome {

    EMITTED,

    REPLAYED,

    SKIPPED_NOT_AUTHORITY,

    SKIPPED_NOT_OWNER,

    SKIPPED_NO_PENDING_PUBLICATION,

    ORIGIN_SOURCE_GAP,

}


