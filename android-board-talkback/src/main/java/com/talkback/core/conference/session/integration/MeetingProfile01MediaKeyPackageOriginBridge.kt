package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.Profile01MembershipConvergenceRegistry
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceKeyMaterial
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentKeyLookupResult
import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageBuildRequest
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageBuildResult
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageBuilder
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageNegativeFixtureVersions
import com.talkback.core.conference.session.profile01.wire.Profile01PackageRecipientBinding
import com.talkback.core.conference.session.profile01.wire.Profile01RecipientEstablishmentKeyLookup
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants
import com.talkback.core.conference.session.profile01.wire.hexToId128Bytes
import com.talkback.core.conference.session.profile01.wire.toHexLower
import com.talkback.core.session.ConferenceTopologySnapshot

/**
 * P1-C MEDIA_KEY_PACKAGE origin/publication bridge.
 *
 * Does not modify CREATION [MeetingProfile01CreationOriginBridge] publication state.
 * Does not send unless establishment binding resolves (no signing-key fallback).
 */
class MeetingProfile01MediaKeyPackageOriginBridge(
    private val sessionIndex: MeetingProfile01ConferenceSessionIndex,
    private val creationOrigin: MeetingProfile01CreationOriginBridge,
    private val mediaKeyAuthority: Profile01ConferenceMediaKeyMaterialAuthority,
    private val establishmentLookup: Profile01RecipientEstablishmentKeyLookup,
    private val packageBuilder: Profile01MediaKeyPackageBuilder?,
    private val membershipConvergence: Profile01MembershipConvergenceRegistry? = null,
    private val publicationLedger: MediaKeyPackagePublicationLedger = MediaKeyPackagePublicationLedger(),
    private val postBindPublicationLedger: MediaKeyPackagePostBindPublicationLedger =
        MediaKeyPackagePostBindPublicationLedger(),
    private val onLog: (String) -> Unit = {},
) {
    fun onEligiblePeers(
        sessionId: String,
        snapshot: ConferenceTopologySnapshot,
        localModuleId: String,
        isMembershipAuthority: Boolean,
        eligiblePeerModuleIds: Set<String>,
        publishToPeer: (moduleId: String, signedFactBytes: ByteArray) -> Boolean,
    ): MediaKeyPackageOriginOutcome {
        if (!isMembershipAuthority) {
            logGap(sessionId, null, "NOT_MEMBERSHIP_AUTHORITY")
            return MediaKeyPackageOriginOutcome.SKIPPED_NOT_AUTHORITY
        }
        if (localModuleId != snapshot.hostModuleId) {
            logGap(sessionId, null, "NOT_HOST_OWNER")
            return MediaKeyPackageOriginOutcome.SKIPPED_NOT_OWNER
        }
        val builder = packageBuilder
        if (builder == null) {
            logGap(sessionId, null, "NO_BUILDER")
            return MediaKeyPackageOriginOutcome.ORIGIN_SOURCE_GAP
        }
        val creationContext =
            resolveActivePackageContext(sessionId, snapshot.hostModuleId)
                ?: return MediaKeyPackageOriginOutcome.SKIPPED_NO_CREATION
        val material =
            mediaKeyAuthority.materialForSession(sessionId)
                ?: return MediaKeyPackageOriginOutcome.SKIPPED_NO_MATERIAL
        val creationDigestHex = creationContext.membershipFactDigestHex
        val conferenceIdHex = sessionIndex.ensureConferenceIdHex(sessionId)
        val priorPublishedCount = publicationLedger.publishedIdentities(sessionId).size

        var publishedThisCall = 0
        eligiblePeerModuleIds
            .filter { it != localModuleId }
            .sorted()
            .forEach { peerModuleId ->
                when (val lookup = establishmentLookup.activeEstablishmentKey(peerModuleId)) {
                    is Profile01EstablishmentKeyLookupResult.Found -> {
                        val identity =
                            MediaKeyPackagePublicationIdentity(
                                conferenceIdHex = conferenceIdHex,
                                mediaKeyEpoch = creationContext.mediaKeyEpoch,
                                recipientModuleId = peerModuleId,
                                recipientEstablishmentKeyVersion = lookup.ref.establishmentKeyVersion,
                                membershipFactDigestHex = creationDigestHex,
                            )
                        if (publicationLedger.isPublished(sessionId, identity)) {
                            return@forEach
                        }
                        val recipient =
                            Profile01PackageRecipientBinding(
                                recipientModuleId = lookup.ref.recipientModuleId,
                                establishmentKeyVersion = lookup.ref.establishmentKeyVersion,
                                establishmentPublicKeySpki = lookup.ref.establishmentPublicKeySpki.copyOf(),
                            )
                        val buildRequest =
                            Profile01MediaKeyPackageBuildRequest(
                                material =
                                    Profile01ConferenceKeyMaterial(
                                        conferenceId = conferenceIdHex.hexToId128Bytes(),
                                        conferenceEpoch = creationContext.conferenceEpoch,
                                        ownerModuleId = creationContext.ownerModuleId,
                                        membershipVersion = creationContext.membershipVersion,
                                        mediaKeyEpoch = creationContext.mediaKeyEpoch,
                                        conferenceMediaSecret = material.conferenceMediaSecret.copyOf(),
                                        membershipKeyContextDigest = material.membershipKeyContextDigest.copyOf(),
                                        mediaKeyCommitment = material.mediaKeyCommitment.copyOf(),
                                    ),
                                creationFactDigest = creationContext.creationFactDigest.copyOf(),
                                recipient = recipient,
                            )
                        when (val built = builder.build(buildRequest)) {
                            is Profile01MediaKeyPackageBuildResult.Rejected -> {
                                logGap(sessionId, peerModuleId, "BUILD_${built.reason}")
                            }
                            is Profile01MediaKeyPackageBuildResult.Ready -> {
                                if (publishToPeer(peerModuleId, built.signedFactBytes)) {
                                    publicationLedger.markPublished(sessionId, identity)
                                    publishedThisCall++
                                    onLog(
                                        "PROFILE01_ORIGIN_EMIT session=$sessionId " +
                                            "factType=${Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE} " +
                                            "target=$peerModuleId outcome=EMITTED " +
                                            "conferenceId=$conferenceIdHex establishmentKeyVersion=" +
                                            "${identity.recipientEstablishmentKeyVersion} " +
                                            "membershipFactDigest=$creationDigestHex",
                                    )
                                    logPackageOrigin(
                                        conferenceIdHex = conferenceIdHex,
                                        mediaKeyEpoch = identity.mediaKeyEpoch,
                                        recipientModuleId = identity.recipientModuleId,
                                        recipientEstablishmentKeyVersion =
                                            identity.recipientEstablishmentKeyVersion,
                                        membershipFactDigest = identity.membershipFactDigestHex,
                                        publicationOutcome = "EMITTED",
                                    )
                                } else {
                                    onLog(
                                        "PROFILE01_ORIGIN_EMIT session=$sessionId " +
                                            "factType=${Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE} " +
                                            "target=$peerModuleId outcome=SEND_FAILED " +
                                            "obligation=OPEN",
                                    )
                                    logPackageOrigin(
                                        conferenceIdHex = conferenceIdHex,
                                        mediaKeyEpoch = identity.mediaKeyEpoch,
                                        recipientModuleId = identity.recipientModuleId,
                                        recipientEstablishmentKeyVersion =
                                            identity.recipientEstablishmentKeyVersion,
                                        membershipFactDigest = identity.membershipFactDigestHex,
                                        publicationOutcome = "SEND_FAILED",
                                    )
                                }
                            }
                        }
                    }
                    is Profile01EstablishmentKeyLookupResult.UnknownModule -> {
                        logGap(sessionId, peerModuleId, "UNKNOWN_ESTABLISHMENT_MODULE")
                    }
                    is Profile01EstablishmentKeyLookupResult.UnknownKeyVersion -> {
                        logGap(sessionId, peerModuleId, "UNKNOWN_ESTABLISHMENT_KEY_VERSION")
                    }
                    is Profile01EstablishmentKeyLookupResult.KeyNotUsable -> {
                        logGap(sessionId, peerModuleId, "ESTABLISHMENT_KEY_NOT_USABLE")
                    }
                }
            }
        return when {
            publishedThisCall == 0 -> MediaKeyPackageOriginOutcome.SKIPPED_NO_PENDING_PUBLICATION
            priorPublishedCount > 0 -> MediaKeyPackageOriginOutcome.REPLAYED
            else -> MediaKeyPackageOriginOutcome.EMITTED
        }
    }

    /**
     * P1-C-R1: republish fresh MEDIA_KEY_PACKAGE after peer is package-consumption eligible.
     *
     * Opens only when initial transport publish succeeded but post-bind delivery is incomplete.
     * Does not replay prior ciphertext bytes.
     */
    fun onPeerPackageConsumptionEligible(
        sessionId: String,
        snapshot: ConferenceTopologySnapshot,
        localModuleId: String,
        isMembershipAuthority: Boolean,
        recipientModuleId: String,
        publishToPeer: (moduleId: String, signedFactBytes: ByteArray) -> Boolean,
    ): MediaKeyPackagePostBindRepublishOutcome {
        if (!isMembershipAuthority) {
            return MediaKeyPackagePostBindRepublishOutcome.SKIPPED_NOT_AUTHORITY
        }
        if (localModuleId != snapshot.hostModuleId) {
            return MediaKeyPackagePostBindRepublishOutcome.SKIPPED_NOT_OWNER
        }
        val builder = packageBuilder
            ?: return MediaKeyPackagePostBindRepublishOutcome.SKIPPED_NO_BUILDER
        val creationContext =
            resolveActivePackageContext(sessionId, snapshot.hostModuleId)
                ?: return MediaKeyPackagePostBindRepublishOutcome.SKIPPED_NO_CREATION
        val material =
            mediaKeyAuthority.materialForSession(sessionId)
                ?: return MediaKeyPackagePostBindRepublishOutcome.SKIPPED_NO_MATERIAL
        val creationDigestHex = creationContext.membershipFactDigestHex
        val conferenceIdHex = sessionIndex.ensureConferenceIdHex(sessionId)

        val lookup = establishmentLookup.activeEstablishmentKey(recipientModuleId)
        if (lookup !is Profile01EstablishmentKeyLookupResult.Found) {
            return MediaKeyPackagePostBindRepublishOutcome.SKIPPED_LOOKUP
        }
        val identity =
            MediaKeyPackagePublicationIdentity(
                conferenceIdHex = conferenceIdHex,
                mediaKeyEpoch = creationContext.mediaKeyEpoch,
                recipientModuleId = recipientModuleId,
                recipientEstablishmentKeyVersion = lookup.ref.establishmentKeyVersion,
                membershipFactDigestHex = creationDigestHex,
            )
        if (!publicationLedger.isPublished(sessionId, identity)) {
            return MediaKeyPackagePostBindRepublishOutcome.SKIPPED_NO_PRE_BIND_PUBLICATION
        }
        if (postBindPublicationLedger.isPostBindDelivered(sessionId, identity)) {
            return MediaKeyPackagePostBindRepublishOutcome.SKIPPED_ALREADY_DELIVERED
        }

        val recipient =
            Profile01PackageRecipientBinding(
                recipientModuleId = lookup.ref.recipientModuleId,
                establishmentKeyVersion = lookup.ref.establishmentKeyVersion,
                establishmentPublicKeySpki = lookup.ref.establishmentPublicKeySpki.copyOf(),
            )
        val buildRequest =
            Profile01MediaKeyPackageBuildRequest(
                material =
                    Profile01ConferenceKeyMaterial(
                        conferenceId = conferenceIdHex.hexToId128Bytes(),
                        conferenceEpoch = creationContext.conferenceEpoch,
                        ownerModuleId = creationContext.ownerModuleId,
                        membershipVersion = creationContext.membershipVersion,
                        mediaKeyEpoch = creationContext.mediaKeyEpoch,
                        conferenceMediaSecret = material.conferenceMediaSecret.copyOf(),
                        membershipKeyContextDigest = material.membershipKeyContextDigest.copyOf(),
                        mediaKeyCommitment = material.mediaKeyCommitment.copyOf(),
                    ),
                creationFactDigest = creationContext.creationFactDigest.copyOf(),
                recipient = recipient,
            )
        return when (val built = builder.build(buildRequest)) {
            is Profile01MediaKeyPackageBuildResult.Rejected -> {
                logPostBindRepublish(
                    conferenceIdHex = conferenceIdHex,
                    identity = identity,
                    publicationOutcome = "BUILD_REJECTED",
                    reason = built.reason,
                )
                MediaKeyPackagePostBindRepublishOutcome.BUILD_REJECTED
            }
            is Profile01MediaKeyPackageBuildResult.Ready -> {
                if (publishToPeer(recipientModuleId, built.signedFactBytes)) {
                    postBindPublicationLedger.markPostBindDelivered(sessionId, identity)
                    logPostBindRepublish(
                        conferenceIdHex = conferenceIdHex,
                        identity = identity,
                        publicationOutcome = "EMITTED",
                    )
                    logPackageOrigin(
                        conferenceIdHex = conferenceIdHex,
                        mediaKeyEpoch = identity.mediaKeyEpoch,
                        recipientModuleId = identity.recipientModuleId,
                        recipientEstablishmentKeyVersion = identity.recipientEstablishmentKeyVersion,
                        membershipFactDigest = identity.membershipFactDigestHex,
                        publicationOutcome = "POST_BIND_EMITTED",
                    )
                    MediaKeyPackagePostBindRepublishOutcome.EMITTED
                } else {
                    logPostBindRepublish(
                        conferenceIdHex = conferenceIdHex,
                        identity = identity,
                        publicationOutcome = "SEND_FAILED",
                    )
                    logPackageOrigin(
                        conferenceIdHex = conferenceIdHex,
                        mediaKeyEpoch = identity.mediaKeyEpoch,
                        recipientModuleId = identity.recipientModuleId,
                        recipientEstablishmentKeyVersion = identity.recipientEstablishmentKeyVersion,
                        membershipFactDigest = identity.membershipFactDigestHex,
                        publicationOutcome = "POST_BIND_SEND_FAILED",
                    )
                    MediaKeyPackagePostBindRepublishOutcome.SEND_FAILED
                }
            }
        }
    }

    /**
     * P1-C field C-F7: emit one recipientKeyVersion-mismatch package for [recipientModuleId].
     *
     * Uses normal builder (correct SPKI wrap) then overrides **only** wire recipientKeyVersion
     * and re-signs. Does **not** mark the positive publication ledger.
     */
    fun emitRecipientKeyVersionMismatchFixture(
        sessionId: String,
        snapshot: ConferenceTopologySnapshot,
        localModuleId: String,
        isMembershipAuthority: Boolean,
        recipientModuleId: String,
        wireRecipientKeyVersion: Long? = null,
        negativeFixtureId: String =
            java.util.UUID.randomUUID().toString().replace("-", "").take(16),
        publishToPeer: (moduleId: String, signedFactBytes: ByteArray) -> Boolean,
    ): MediaKeyPackageNegativeFixtureOutcome {
        if (!isMembershipAuthority) {
            return MediaKeyPackageNegativeFixtureOutcome.SKIPPED_NOT_AUTHORITY
        }
        if (localModuleId != snapshot.hostModuleId) {
            return MediaKeyPackageNegativeFixtureOutcome.SKIPPED_NOT_OWNER
        }
        val builder = packageBuilder
            ?: return MediaKeyPackageNegativeFixtureOutcome.SKIPPED_NO_BUILDER
        val creationContext =
            resolveActivePackageContext(sessionId, snapshot.hostModuleId)
                ?: return MediaKeyPackageNegativeFixtureOutcome.SKIPPED_NO_CREATION
        val material =
            mediaKeyAuthority.materialForSession(sessionId)
                ?: return MediaKeyPackageNegativeFixtureOutcome.SKIPPED_NO_MATERIAL
        val conferenceIdHex = sessionIndex.ensureConferenceIdHex(sessionId)
        val creationDigestHex = creationContext.membershipFactDigestHex

        val lookup = establishmentLookup.activeEstablishmentKey(recipientModuleId)
        if (lookup !is Profile01EstablishmentKeyLookupResult.Found) {
            onLog(
                "PROFILE01_MEDIA_KEY_PACKAGE_NEGATIVE_FIXTURE " +
                    "fixtureType=RECIPIENT_KEY_VERSION_MISMATCH " +
                    "negativeFixtureId=$negativeFixtureId injected=false " +
                    "reason=LOOKUP_${lookup::class.simpleName}",
            )
            return MediaKeyPackageNegativeFixtureOutcome.SKIPPED_LOOKUP
        }
        val expectedVersion = lookup.ref.establishmentKeyVersion
        val wireVersion =
            wireRecipientKeyVersion
                ?: Profile01MediaKeyPackageNegativeFixtureVersions.chooseWireMismatchVersion(
                    expectedVersion,
                )
        if (wireVersion == expectedVersion) {
            onLog(
                "PROFILE01_MEDIA_KEY_PACKAGE_NEGATIVE_FIXTURE " +
                    "fixtureType=RECIPIENT_KEY_VERSION_MISMATCH " +
                    "negativeFixtureId=$negativeFixtureId injected=false " +
                    "reason=WIRE_VERSION_NOT_DISTINCT",
            )
            return MediaKeyPackageNegativeFixtureOutcome.OVERRIDE_REJECTED
        }

        val recipient =
            Profile01PackageRecipientBinding(
                recipientModuleId = lookup.ref.recipientModuleId,
                establishmentKeyVersion = expectedVersion,
                establishmentPublicKeySpki = lookup.ref.establishmentPublicKeySpki.copyOf(),
            )
        val buildRequest =
            Profile01MediaKeyPackageBuildRequest(
                material =
                    Profile01ConferenceKeyMaterial(
                        conferenceId = conferenceIdHex.hexToId128Bytes(),
                        conferenceEpoch = creationContext.conferenceEpoch,
                        ownerModuleId = creationContext.ownerModuleId,
                        membershipVersion = creationContext.membershipVersion,
                        mediaKeyEpoch = creationContext.mediaKeyEpoch,
                        conferenceMediaSecret = material.conferenceMediaSecret.copyOf(),
                        membershipKeyContextDigest = material.membershipKeyContextDigest.copyOf(),
                        mediaKeyCommitment = material.mediaKeyCommitment.copyOf(),
                    ),
                creationFactDigest = creationContext.creationFactDigest.copyOf(),
                recipient = recipient,
            )
        val built =
            when (val result = builder.build(buildRequest)) {
                is Profile01MediaKeyPackageBuildResult.Rejected -> {
                    onLog(
                        "PROFILE01_MEDIA_KEY_PACKAGE_NEGATIVE_FIXTURE " +
                            "fixtureType=RECIPIENT_KEY_VERSION_MISMATCH " +
                            "negativeFixtureId=$negativeFixtureId injected=false " +
                            "reason=BUILD_${result.reason}",
                    )
                    return MediaKeyPackageNegativeFixtureOutcome.BUILD_REJECTED
                }
                is Profile01MediaKeyPackageBuildResult.Ready -> result
            }
        val overridden =
            when (
                val result =
                    builder.overrideRecipientKeyVersionAndResign(
                        built.signedFactBytes,
                        wireVersion,
                    )
            ) {
                is Profile01MediaKeyPackageBuildResult.Rejected -> {
                    onLog(
                        "PROFILE01_MEDIA_KEY_PACKAGE_NEGATIVE_FIXTURE " +
                            "fixtureType=RECIPIENT_KEY_VERSION_MISMATCH " +
                            "negativeFixtureId=$negativeFixtureId injected=false " +
                            "reason=OVERRIDE_${result.reason}",
                    )
                    return MediaKeyPackageNegativeFixtureOutcome.OVERRIDE_REJECTED
                }
                is Profile01MediaKeyPackageBuildResult.Ready -> result
            }

        val sent = publishToPeer(recipientModuleId, overridden.signedFactBytes)
        val publicationOutcome = if (sent) "EMITTED" else "SEND_FAILED"
        onLog(
            "PROFILE01_MEDIA_KEY_PACKAGE_NEGATIVE_FIXTURE " +
                "fixtureType=RECIPIENT_KEY_VERSION_MISMATCH " +
                "negativeFixtureId=$negativeFixtureId " +
                "injected=${if (sent) "true" else "false"} " +
                "conferenceId=$conferenceIdHex " +
                "mediaKeyEpoch=${creationContext.mediaKeyEpoch} " +
                "recipientModuleId=$recipientModuleId " +
                "expectedRecipientEstablishmentKeyVersion=$expectedVersion " +
                "wireRecipientKeyVersion=$wireVersion " +
                "membershipFactDigest=$creationDigestHex " +
                "publicationOutcome=$publicationOutcome",
        )
        return if (sent) {
            MediaKeyPackageNegativeFixtureOutcome.EMITTED
        } else {
            MediaKeyPackageNegativeFixtureOutcome.SEND_FAILED
        }
    }

    fun clearSession(sessionId: String) {
        publicationLedger.clearSession(sessionId)
        postBindPublicationLedger.clearSession(sessionId)
    }

    internal fun ledger(): MediaKeyPackagePublicationLedger = publicationLedger

    internal fun postBindLedger(): MediaKeyPackagePostBindPublicationLedger = postBindPublicationLedger

    private data class CreationWireContext(
        val creationFactDigest: ByteArray,
        val membershipFactDigestHex: String,
        val conferenceEpoch: Long,
        val ownerModuleId: String,
        val membershipVersion: Long,
        val mediaKeyEpoch: Long,
    ) {
        val creationFactDigestHex: String = membershipFactDigestHex
    }

    private fun resolveActivePackageContext(
        sessionId: String,
        hostModuleId: String,
    ): CreationWireContext? {
        val creationBytes = creationOrigin.readSignedCreationFact(sessionId) ?: return null
        val creationContext = decodeCreationContext(creationBytes, hostModuleId) ?: return null
        val conferenceIdHex = sessionIndex.conferenceIdForSession(sessionId) ?: return creationContext
        val generation = membershipConvergence?.current(conferenceIdHex) ?: return creationContext
        if (generation.membershipVersion == 0L) {
            return creationContext
        }
        return CreationWireContext(
            creationFactDigest = generation.generationFactDigest.copyOf(),
            membershipFactDigestHex = generation.generationFactDigest.toHexLower(),
            conferenceEpoch = generation.conferenceEpoch,
            ownerModuleId = hostModuleId,
            membershipVersion = generation.membershipVersion,
            mediaKeyEpoch = generation.mediaKeyEpoch,
        )
    }

    private fun decodeCreationContext(
        signedFactBytes: ByteArray,
        ownerModuleId: String,
    ): CreationWireContext? {
        val envelope =
            Profile01SignedFactEnvelope.parse(signedFactBytes) ?: return null
        val digest = Profile01FactDigest.computeFactDigest(envelope.fullCanonicalBytes)
        val placeholder =
            Profile01SessionMediaSupplement(
                channelId = "origin-read",
                masterKey = ByteArray(16),
                masterSalt = ByteArray(12),
                keyContextHint64 = ByteArray(8),
            )
        return when (
            val decoded =
                Profile01WireCborDecoder.decodeCreationSession(signedFactBytes, placeholder)
        ) {
            is Profile01WireCborDecoder.DecodeResult.Ready ->
                CreationWireContext(
                    creationFactDigest = digest.copyOf(),
                    membershipFactDigestHex = digest.toHexLower(),
                    conferenceEpoch = decoded.value.conferenceEpoch,
                    ownerModuleId = ownerModuleId,
                    membershipVersion = decoded.value.membershipVersion,
                    mediaKeyEpoch = decoded.value.mediaKeyEpoch,
                )
            else -> null
        }
    }

    private fun logGap(
        sessionId: String,
        peerModuleId: String?,
        reason: String,
    ) {
        val target = peerModuleId?.let { " target=$it" } ?: ""
        onLog(
            "PROFILE01_ORIGIN_EMIT session=$sessionId$target " +
                "factType=${Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE} " +
                "outcome=ORIGIN_SOURCE_GAP reason=$reason",
        )
    }

    /** P1-C-FIELD-OBS — structured origin facts only; no secret material. */
    private fun logPackageOrigin(
        conferenceIdHex: String,
        mediaKeyEpoch: Long,
        recipientModuleId: String,
        recipientEstablishmentKeyVersion: Long,
        membershipFactDigest: String,
        publicationOutcome: String,
    ) {
        onLog(
            "PROFILE01_MEDIA_KEY_PACKAGE_ORIGIN " +
                "conferenceId=$conferenceIdHex " +
                "mediaKeyEpoch=$mediaKeyEpoch " +
                "recipientModuleId=$recipientModuleId " +
                "recipientEstablishmentKeyVersion=$recipientEstablishmentKeyVersion " +
                "membershipFactDigest=$membershipFactDigest " +
                "publicationOutcome=$publicationOutcome",
        )
    }

    private fun logPostBindRepublish(
        conferenceIdHex: String,
        identity: MediaKeyPackagePublicationIdentity,
        publicationOutcome: String,
        reason: String? = null,
    ) {
        val reasonSuffix = reason?.let { " reason=$it" } ?: ""
        onLog(
            "PROFILE01_MEDIA_KEY_PACKAGE_POST_BIND_REPUBLISH " +
                "conferenceId=$conferenceIdHex " +
                "mediaKeyEpoch=${identity.mediaKeyEpoch} " +
                "recipientModuleId=${identity.recipientModuleId} " +
                "recipientEstablishmentKeyVersion=${identity.recipientEstablishmentKeyVersion} " +
                "membershipFactDigest=${identity.membershipFactDigestHex} " +
                "publicationOutcome=$publicationOutcome$reasonSuffix",
        )
    }
}
