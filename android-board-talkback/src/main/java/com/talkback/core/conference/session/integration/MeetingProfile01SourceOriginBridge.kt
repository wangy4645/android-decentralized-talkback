package com.talkback.core.conference.session.integration



import com.talkback.core.conference.session.profile01.Profile01MembershipConvergenceRegistry
import com.talkback.core.conference.session.profile01.Profile01MembershipGeneration

import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry

import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority

import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest

import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope

import com.talkback.core.conference.session.profile01.wire.Profile01SourceDeclarationAuthoritySnapshot

import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants

import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder

import com.talkback.core.conference.session.profile01.wire.hexToId128Bytes

import com.talkback.core.conference.session.profile01.wire.toHexLower

import com.talkback.core.session.ConferenceTopologySnapshot

import java.util.concurrent.ConcurrentHashMap



/**

 * PR-PA-SR-B1 SOURCE_DECLARATION origin/publication bridge.

 *

 * Self-declaration only 鈥?any roster member may originate its own SOURCE (B1-HC1).

 * Post-bind republication replays cached signed bytes byte-identically (B1-HC3).

 * PR-PA-SR-B1-R1 adds membership-eligibility continuation obligations.

 *

 * Publication adapter only — it does not number incarnations. The wire `sourceGeneration`
 * is the authority commitment generation verbatim, so
 * `wire.sourceGeneration == registry.membershipIncarnationId == authority.sourceGeneration`
 * holds and the shadow TX seam has a single generation to fence against. Identity comparison
 * still decides build/rebuild and idempotency; it never derives a generation number.
 * Generations may skip (commitments made while the build is in ORIGIN_SOURCE_GAP are real
 * incarnation decisions); frozen semantics require monotonic succession, not contiguity.

 */

class MeetingProfile01SourceOriginBridge(

    private val sessionIndex: MeetingProfile01ConferenceSessionIndex,

    private val mediaKeyAuthority: Profile01ConferenceMediaKeyMaterialAuthority,

    private val supplementRegistry: Profile01SessionMediaSupplementRegistry,

    private val membershipConvergence: Profile01MembershipConvergenceRegistry,

    private val publisher: MeetingProfile01SourceOriginPublisher?,

    private val publicationLedger: SourcePublicationLedger = SourcePublicationLedger(),

    private val postBindPublicationLedger: SourcePostBindPublicationLedger =
        SourcePostBindPublicationLedger(),

    private val eligibilityObligationStore: SourceOriginEligibilityObligationStore =

        SourceOriginEligibilityObligationStore(),

    private val onLog: (String) -> Unit = {},

) {

    @Volatile
    var memberBindingMaterializer: Profile01ShadowMemberBindingMaterializer? = null

    private data class SourcePublicationState(

        val signedFactBytes: ByteArray,

        /**
         * Generation of the authority commitment this publication carries.
         *
         * Recorded for idempotency and stale detection only 鈥?the bridge does not derive
         * incarnation numbers. [Profile01LocalConferenceSourceIdentityAuthority] owns them.
         */
        val publishedAuthorityGeneration: Long,

        val factDigestHex: String,

        val publishedToPeers: MutableSet<String> = ConcurrentHashMap.newKeySet(),

    )



    private data class CommittedSourceIdentity(

        val ssrc: Int,

        val sourceInstanceId: ByteArray,

        val mediaGroupDescriptorDigest: ByteArray,

    )



    private val sourceStateBySession = ConcurrentHashMap<String, SourcePublicationState>()

    private val committedIdentityBySession = ConcurrentHashMap<String, CommittedSourceIdentity>()



    fun onLocalConferenceSourceCommitted(

        sessionId: String,

        localModuleId: String,

        authoritySourceGeneration: Long,

        ssrc: Int,

        sourceInstanceId: ByteArray,

        mediaGroupDescriptorDigest: ByteArray,

        continuationTrigger: String? = null,

    ): SourceOriginBuildOutcome {

        val pub = publisher

        if (pub == null) {

            logGap(sessionId, localModuleId, "NO_SIGNER")

            return SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP

        }

        if (localModuleId != pub.signerModuleId) {

            logGap(sessionId, localModuleId, "SIGNER_MODULE_MISMATCH")

            return SourceOriginBuildOutcome.SKIPPED_NOT_SELF

        }

        val conferenceIdHex = sessionIndex.conferenceIdForSession(sessionId)

        if (conferenceIdHex == null) {

            logGap(sessionId, localModuleId, "NO_CONFERENCE_ID")

            return SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP

        }

        eligibilityObligationStore.discardOtherGenerations(

            sessionId = sessionId,

            localModuleId = localModuleId,

            authoritySourceGeneration = authoritySourceGeneration,

        )

        val convergence = membershipConvergence.current(conferenceIdHex)

        val reconcile =

            eligibilityObligationStore.reconcile(

                sessionId = sessionId,

                localModuleId = localModuleId,

                authoritySourceGeneration = authoritySourceGeneration,

                sourceInstanceId = sourceInstanceId,

                conferenceIdHex = conferenceIdHex,

                convergence = convergence,

            )

        if (reconcile == SourceOriginObligationReconcileOutcome.DISCARDED_STALE) {

            logContinuation(

                sessionId = sessionId,

                conferenceIdHex = conferenceIdHex,

                moduleId = localModuleId,

                action = "DISCARDED_STALE",

                trigger = continuationTrigger,

                authoritySourceGeneration = authoritySourceGeneration,

            )

        }

        val generation =

            convergence

                ?: run {

                    retainMembershipEligibilityObligation(

                        sessionId = sessionId,

                        conferenceIdHex = conferenceIdHex,

                        localModuleId = localModuleId,

                        authoritySourceGeneration = authoritySourceGeneration,

                        sourceInstanceId = sourceInstanceId,

                        membershipVersion = SourceOriginEligibilityObligationStore.UNKNOWN_MEMBERSHIP_VERSION,

                        mediaKeyEpoch = SourceOriginEligibilityObligationStore.UNKNOWN_MEDIA_KEY_EPOCH,

                        continuationTrigger = continuationTrigger,

                    )

                    logGap(sessionId, localModuleId, "MEMBERSHIP_NOT_CONVERGED")

                    return SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP

                }

        if (!generation.hasMember(localModuleId)) {

            retainMembershipEligibilityObligation(

                sessionId = sessionId,

                conferenceIdHex = conferenceIdHex,

                localModuleId = localModuleId,

                authoritySourceGeneration = authoritySourceGeneration,

                sourceInstanceId = sourceInstanceId,

                membershipVersion = generation.membershipVersion,

                mediaKeyEpoch = generation.mediaKeyEpoch,

                continuationTrigger = continuationTrigger,

            )

            logGap(sessionId, localModuleId, "NOT_IN_ROSTER")

            return SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP

        }

        if (!isMediaMaterialReady(sessionId, conferenceIdHex, generation.mediaKeyEpoch)) {

            logGap(sessionId, localModuleId, "NO_MEDIA_MATERIAL")

            return SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP

        }

        val identity =

            CommittedSourceIdentity(

                ssrc = ssrc,

                sourceInstanceId = sourceInstanceId.copyOf(),

                mediaGroupDescriptorDigest = mediaGroupDescriptorDigest.copyOf(),

            )

        val priorIdentity = committedIdentityBySession[sessionId]

        val existingState = sourceStateBySession[sessionId]

        if (priorIdentity != null &&
            priorIdentity.matches(identity) &&
            existingState != null &&
            existingState.publishedAuthorityGeneration == authoritySourceGeneration &&
            signedSourceMatchesGeneration(existingState.signedFactBytes, generation)
        ) {
            consumeEligibilityObligation(sessionId, localModuleId, authoritySourceGeneration, continuationTrigger)
            return SourceOriginBuildOutcome.ALREADY_BUILT
        }

        if (existingState != null && authoritySourceGeneration < existingState.publishedAuthorityGeneration) {
            logGap(sessionId, localModuleId, "STALE_AUTHORITY_GENERATION")
            return SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP
        }

        val sourceGeneration = authoritySourceGeneration

        val snapshot =

            Profile01SourceDeclarationAuthoritySnapshot(

                conferenceId = conferenceIdHex.hexToId128Bytes(),

                conferenceEpoch = generation.conferenceEpoch,

                membershipVersion = generation.membershipVersion,

                mediaKeyEpoch = generation.mediaKeyEpoch,

                declaringModuleId = localModuleId,

                sourceGeneration = sourceGeneration,

                sourceInstanceId = identity.sourceInstanceId.copyOf(),

                ssrc = identity.ssrc,

                mediaGroupDescriptorDigest = identity.mediaGroupDescriptorDigest.copyOf(),

                signerKeyVersion = pub.signerKeyVersion,

            )

        val signedFactBytes = pub.publishSource(snapshot)

        val digestHex =

            Profile01SignedFactEnvelope.parse(signedFactBytes)?.let { envelope ->

                Profile01FactDigest.computeFactDigest(envelope.fullCanonicalBytes).toHexLower()

            } ?: "unknown"

        committedIdentityBySession[sessionId] = identity

        sourceStateBySession[sessionId] =

            SourcePublicationState(

                signedFactBytes = signedFactBytes.copyOf(),

                publishedAuthorityGeneration = sourceGeneration,

                factDigestHex = digestHex,

            )

        consumeEligibilityObligation(sessionId, localModuleId, authoritySourceGeneration, continuationTrigger)

        onLog(

            "PROFILE01_ORIGIN_EMIT session=$sessionId factType=${Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION} " +

                "moduleId=$localModuleId outcome=BUILT conferenceId=$conferenceIdHex " +

                "sourceGeneration=$sourceGeneration factDigest=$digestHex",

        )

        memberBindingMaterializer?.onLocalSourceBuilt(

            sessionId = sessionId,

            moduleId = localModuleId,

            signedFactBytes = signedFactBytes.copyOf(),

        )

        return SourceOriginBuildOutcome.BUILT

    }



    fun onEligiblePeers(

        sessionId: String,

        snapshot: ConferenceTopologySnapshot,

        localModuleId: String,

        eligiblePeerModuleIds: Set<String>,

        publishToPeer: (moduleId: String, signedFactBytes: ByteArray) -> Boolean,

    ): SourceOriginPublishOutcome {

        val state = sourceStateBySession[sessionId]

        if (state == null) {

            logGap(sessionId, localModuleId, "NO_BUILT_SOURCE")

            return SourceOriginPublishOutcome.SKIPPED_NO_BUILT_SOURCE

        }

        if (!snapshot.members.contains(localModuleId)) {

            logGap(sessionId, localModuleId, "NOT_IN_TOPOLOGY")

            return SourceOriginPublishOutcome.SKIPPED_NOT_IN_ROSTER

        }

        val conferenceIdHex = sessionIndex.conferenceIdForSession(sessionId) ?: return SourceOriginPublishOutcome.ORIGIN_SOURCE_GAP

        var publishedThisCall = 0

        val priorPublishedCount = state.publishedToPeers.size

        eligiblePeerModuleIds

            .filter { it != localModuleId }

            .sorted()

            .forEach { peerModuleId ->

                val identity =

                    SourcePublicationIdentity(

                        conferenceIdHex = conferenceIdHex,

                        sourceGeneration = state.publishedAuthorityGeneration,

                        factDigestHex = state.factDigestHex,

                        peerModuleId = peerModuleId,

                    )

                if (publicationLedger.isPublished(sessionId, identity)) {

                    return@forEach

                }

                val cachedBytes = state.signedFactBytes.copyOf()

                if (publishToPeer(peerModuleId, cachedBytes)) {

                    publicationLedger.markPublished(sessionId, identity)

                    state.publishedToPeers.add(peerModuleId)

                    publishedThisCall++

                    onLog(

                        "PROFILE01_ORIGIN_EMIT session=$sessionId " +

                            "factType=${Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION} " +

                            "moduleId=$localModuleId target=$peerModuleId outcome=EMITTED " +

                            "sourceGeneration=${state.publishedAuthorityGeneration} factDigest=${state.factDigestHex}",

                    )

                } else {

                    onLog(

                        "PROFILE01_ORIGIN_EMIT session=$sessionId " +

                            "factType=${Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION} " +

                            "moduleId=$localModuleId target=$peerModuleId outcome=SEND_FAILED",

                    )

                }

            }

        return when {

            publishedThisCall == 0 -> SourceOriginPublishOutcome.SKIPPED_NO_PENDING_PUBLICATION

            priorPublishedCount > 0 -> SourceOriginPublishOutcome.REPLAYED

            else -> SourceOriginPublishOutcome.EMITTED

        }

    }

    /**
     * B1-R2: replay cached signed SOURCE bytes after peer is post-bind consumption-eligible.
     *
     * Transport [publicationLedger] must show pre-bind publish; post-bind satisfaction is tracked
     * separately in [postBindPublicationLedger].
     */
    fun onPeerSourceConsumptionEligible(
        sessionId: String,
        snapshot: ConferenceTopologySnapshot,
        localModuleId: String,
        targetModuleId: String,
        reason: String,
        publishToPeer: (moduleId: String, signedFactBytes: ByteArray) -> Boolean,
    ): SourcePostBindRepublishOutcome {
        val state = sourceStateBySession[sessionId]
            ?: return SourcePostBindRepublishOutcome.SKIPPED_NO_BUILT_SOURCE

        val committed = committedIdentityBySession[sessionId]
            ?: return SourcePostBindRepublishOutcome.SKIPPED_STALE_IDENTITY

        val pub = publisher
        if (pub == null || localModuleId != pub.signerModuleId) {
            return SourcePostBindRepublishOutcome.SKIPPED_NOT_SELF_ORIGIN
        }

        if (!snapshot.members.contains(localModuleId)) {
            return SourcePostBindRepublishOutcome.SKIPPED_NOT_IN_TOPOLOGY
        }

        val conferenceIdHex = sessionIndex.conferenceIdForSession(sessionId)
            ?: return SourcePostBindRepublishOutcome.SKIPPED_STALE_IDENTITY

        val cachedBytes = state.signedFactBytes.copyOf()
        val wire =
            when (val decoded = Profile01WireCborDecoder.decodeSourceDeclarationMember(cachedBytes)) {
                is Profile01WireCborDecoder.DecodeResult.Ready -> decoded.value
                else -> return SourcePostBindRepublishOutcome.SKIPPED_STALE_IDENTITY
            }

        if (!verifyAuthoritativeSourceTuple(state, committed, wire, localModuleId)) {
            return SourcePostBindRepublishOutcome.SKIPPED_STALE_IDENTITY
        }

        val convergence = membershipConvergence.current(conferenceIdHex)
        if (convergence != null &&
            (
                wire.membershipVersion != convergence.membershipVersion ||
                    wire.mediaKeyEpoch != convergence.mediaKeyEpoch
            )
        ) {
            logPostBindRepublish(
                sessionId = sessionId,
                identity =
                    SourcePostBindPublicationIdentity(
                        conferenceId = conferenceIdHex,
                        sourceModuleId = localModuleId,
                        sourceGeneration = state.publishedAuthorityGeneration,
                        sourceInstanceIdHex = committed.sourceInstanceId.toHexLower(),
                        targetModuleId = targetModuleId,
                        factDigest = state.factDigestHex,
                        membershipVersion = wire.membershipVersion,
                        mediaKeyEpoch = wire.mediaKeyEpoch,
                    ),
                reason = reason,
                outcome = "SKIPPED_STALE_MEMBERSHIP_CONTEXT",
            )
            return SourcePostBindRepublishOutcome.SKIPPED_STALE_MEMBERSHIP_CONTEXT
        }

        val transportIdentity =
            SourcePublicationIdentity(
                conferenceIdHex = conferenceIdHex,
                sourceGeneration = state.publishedAuthorityGeneration,
                factDigestHex = state.factDigestHex,
                peerModuleId = targetModuleId,
            )
        if (!publicationLedger.isPublished(sessionId, transportIdentity)) {
            return SourcePostBindRepublishOutcome.SKIPPED_NO_PRE_BIND_PUBLICATION
        }

        val postBindIdentity =
            SourcePostBindPublicationIdentity(
                conferenceId = conferenceIdHex,
                sourceModuleId = localModuleId,
                sourceGeneration = state.publishedAuthorityGeneration,
                sourceInstanceIdHex = committed.sourceInstanceId.toHexLower(),
                targetModuleId = targetModuleId,
                factDigest = state.factDigestHex,
                membershipVersion = wire.membershipVersion,
                mediaKeyEpoch = wire.mediaKeyEpoch,
            )

        if (postBindPublicationLedger.isSatisfied(sessionId, postBindIdentity)) {
            logPostBindRepublish(
                sessionId = sessionId,
                identity = postBindIdentity,
                reason = reason,
                outcome = "ALREADY_SATISFIED",
            )
            return SourcePostBindRepublishOutcome.ALREADY_SATISFIED
        }

        logPostBindRepublish(
            sessionId = sessionId,
            identity = postBindIdentity,
            reason = reason,
            outcome = "ATTEMPTED",
        )

        if (publishToPeer(targetModuleId, cachedBytes)) {
            postBindPublicationLedger.markSatisfied(sessionId, postBindIdentity)
            logPostBindRepublish(
                sessionId = sessionId,
                identity = postBindIdentity,
                reason = reason,
                outcome = "EMITTED",
            )
            return SourcePostBindRepublishOutcome.EMITTED
        }

        logPostBindRepublish(
            sessionId = sessionId,
            identity = postBindIdentity,
            reason = reason,
            outcome = "FAILED",
        )
        return SourcePostBindRepublishOutcome.SEND_FAILED
    }

    /**
     * Converged `mediaKeyEpoch` for the session, i.e. the epoch a SOURCE incarnation must be
     * committed against. Same convergence read used when building the signed Declaration.
     */
    fun currentAuthoritativeMediaKeyEpoch(sessionId: String): Long? {
        val conferenceIdHex = sessionIndex.conferenceIdForSession(sessionId) ?: return null
        return membershipConvergence.current(conferenceIdHex)?.mediaKeyEpoch
    }

    fun readSignedSourceFact(sessionId: String): ByteArray? =

        sourceStateBySession[sessionId]?.signedFactBytes?.copyOf()



    fun clearSession(sessionId: String) {

        sourceStateBySession.remove(sessionId)

        committedIdentityBySession.remove(sessionId)

        publicationLedger.clearSession(sessionId)

        postBindPublicationLedger.clearSession(sessionId)

        eligibilityObligationStore.clearSession(sessionId)

    }



    internal fun ledger(): SourcePublicationLedger = publicationLedger

    internal fun postBindLedger(): SourcePostBindPublicationLedger = postBindPublicationLedger



    internal fun eligibilityObligations(): SourceOriginEligibilityObligationStore = eligibilityObligationStore



    private fun retainMembershipEligibilityObligation(

        sessionId: String,

        conferenceIdHex: String,

        localModuleId: String,

        authoritySourceGeneration: Long,

        sourceInstanceId: ByteArray,

        membershipVersion: Long,

        mediaKeyEpoch: Long,

        continuationTrigger: String?,

    ) {

        val outcome =

            eligibilityObligationStore.retain(

                SourceOriginEligibilityObligation(

                    sessionId = sessionId,

                    conferenceIdHex = conferenceIdHex,

                    localModuleId = localModuleId,

                    sourceInstanceId = sourceInstanceId.copyOf(),

                    authoritySourceGeneration = authoritySourceGeneration,

                    membershipVersion = membershipVersion,

                    mediaKeyEpoch = mediaKeyEpoch,

                ),

            )

        if (outcome == SourceOriginObligationRetainOutcome.RETAINED) {

            logContinuation(

                sessionId = sessionId,

                conferenceIdHex = conferenceIdHex,

                moduleId = localModuleId,

                action = "RETAINED",

                trigger = continuationTrigger,

                authoritySourceGeneration = authoritySourceGeneration,

            )

        }

    }



    private fun consumeEligibilityObligation(

        sessionId: String,

        localModuleId: String,

        authoritySourceGeneration: Long,

        continuationTrigger: String?,

    ) {

        if (!eligibilityObligationStore.hasPending(sessionId, localModuleId, authoritySourceGeneration)) {

            return

        }

        eligibilityObligationStore.consume(sessionId, localModuleId, authoritySourceGeneration)

        val conferenceIdHex = sessionIndex.conferenceIdForSession(sessionId) ?: return

        logContinuation(

            sessionId = sessionId,

            conferenceIdHex = conferenceIdHex,

            moduleId = localModuleId,

            action = if (continuationTrigger != null) "REEVALUATED" else "DISCARDED_BUILT",

            trigger = continuationTrigger,

            authoritySourceGeneration = authoritySourceGeneration,

        )

    }



    private fun isMediaMaterialReady(

        sessionId: String,

        conferenceIdHex: String,

        mediaKeyEpoch: Long,

    ): Boolean {

        if (mediaKeyAuthority.materialForSession(sessionId) != null) return true

        return supplementRegistry.lookup(conferenceIdHex, mediaKeyEpoch) != null

    }



    private fun signedSourceMatchesGeneration(
        signedFactBytes: ByteArray,
        generation: Profile01MembershipGeneration,
    ): Boolean {
        val wire =
            when (val decoded = Profile01WireCborDecoder.decodeSourceDeclarationMember(signedFactBytes)) {
                is Profile01WireCborDecoder.DecodeResult.Ready -> decoded.value
                else -> return false
            }
        return wire.membershipVersion == generation.membershipVersion &&
            wire.mediaKeyEpoch == generation.mediaKeyEpoch
    }

    private fun CommittedSourceIdentity.matches(other: CommittedSourceIdentity): Boolean =

        ssrc == other.ssrc &&

            sourceInstanceId.contentEquals(other.sourceInstanceId) &&

            mediaGroupDescriptorDigest.contentEquals(other.mediaGroupDescriptorDigest)

    private fun verifyAuthoritativeSourceTuple(
        state: SourcePublicationState,
        committed: CommittedSourceIdentity,
        wire: com.talkback.core.conference.session.profile01.Profile01WireMemberSourceFact,
        localModuleId: String,
    ): Boolean {
        if (wire.moduleId != localModuleId) return false
        if (wire.sourceGeneration != state.publishedAuthorityGeneration) return false
        if (wire.ssrc != committed.ssrc) return false
        val digestHex =
            Profile01SignedFactEnvelope.parse(state.signedFactBytes)?.let { envelope ->
                Profile01FactDigest.computeFactDigest(envelope.fullCanonicalBytes).toHexLower()
            } ?: return false
        if (digestHex != state.factDigestHex) return false
        return true
    }

    private fun logPostBindRepublish(
        sessionId: String,
        identity: SourcePostBindPublicationIdentity,
        reason: String,
        outcome: String,
    ) {
        onLog(
            "PROFILE01_SOURCE_POST_BIND_REPUBLISH session=$sessionId " +
                "conferenceId=${identity.conferenceId} sourceModuleId=${identity.sourceModuleId} " +
                "target=${identity.targetModuleId} sourceGeneration=${identity.sourceGeneration} " +
                "factDigest=${identity.factDigest} membershipVersion=${identity.membershipVersion} " +
                "mediaKeyEpoch=${identity.mediaKeyEpoch} reason=$reason outcome=$outcome",
        )
    }

    private fun logGap(

        sessionId: String,

        moduleId: String,

        reason: String,

    ) {

        onLog(

            "PROFILE01_ORIGIN_EMIT session=$sessionId factType=${Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION} " +

                "moduleId=$moduleId outcome=ORIGIN_SOURCE_GAP reason=$reason",

        )

    }



    private fun logContinuation(

        sessionId: String,

        conferenceIdHex: String,

        moduleId: String,

        action: String,

        trigger: String?,

        authoritySourceGeneration: Long,

    ) {

        onLog(

            "PROFILE01_SOURCE_ELIGIBILITY_CONTINUATION session=$sessionId conferenceId=$conferenceIdHex " +

                "moduleId=$moduleId action=$action trigger=${trigger ?: "-"} " +

                "obligationKey=$sessionId|$moduleId|$authoritySourceGeneration",

        )

    }

}



enum class SourceOriginBuildOutcome {

    BUILT,

    ALREADY_BUILT,

    SKIPPED_NOT_SELF,

    ORIGIN_SOURCE_GAP,

}



enum class SourceOriginPublishOutcome {

    EMITTED,

    REPLAYED,

    SKIPPED_NO_BUILT_SOURCE,

    SKIPPED_NOT_IN_ROSTER,

    SKIPPED_NO_PENDING_PUBLICATION,

    ORIGIN_SOURCE_GAP,

}


