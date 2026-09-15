package com.talkback.core.conference.session.profile01

import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.gbc.AuthoritativeConferenceMediaSessionDeclaration
import com.talkback.core.conference.session.gbc.ConferenceMediaDeclarationDisposition
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptResult
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptSeam
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.wire.toHexLower

/**
 * Profile 01 verified fact → GBC publisher bridge ingress.
 *
 * Validates wire facts, maps to authoritative declarations, publishes into registry.
 * Does not call [com.talkback.core.conference.session.ConferenceSessionMediaWiring] directly.
 */
class Profile01ConferenceMediaFactIngress(
    private val validator: Profile01ConferenceMediaFactValidator,
    private val publisherBridge: ConferenceSessionMediaGbcPublisherBridge,
    private val mediaKeyDecrypt: Profile01MediaKeyPackageDecryptSeam? = null,
    private val supplementRegistry: Profile01SessionMediaSupplementRegistry? = null,
    private val onLog: (String) -> Unit = {},
) {
    /** Optional product hook when decrypted media supplement becomes READY (PR-PA-SR-B1). */
    var onMediaSupplementReady: ((conferenceId: String, mediaKeyEpoch: Long) -> Unit)? = null
    /** PR-PA-SR-B1-R1 R1-B: peer CREATION APPLIED / membership head established. */
    var onCreationMembershipEstablished: ((conferenceId: String) -> Unit)? = null
    /** Test-only seam visibility for PR-P1D-2 factory composition exit tests. */
    internal fun mediaKeyDecryptSeam(): Profile01MediaKeyPackageDecryptSeam? = mediaKeyDecrypt

    fun membershipRegistry(): Profile01MembershipConvergenceRegistry = validator.membershipRegistry()

    fun currentMembershipGeneration(conferenceId: String): Profile01MembershipGeneration? =
        validator.membershipRegistry().current(conferenceId)

    fun hasPublishedSession(conferenceId: String): Boolean =
        publisherBridge.publishedSession(conferenceId) != null

    /**
     * RCA4b — materialize session registry at membership head when CREATION is still
     * INCOMPLETE_PENDING at a stale mediaKeyEpoch but the head supplement is READY.
     */
    fun tryMaterializeSessionAtMembershipHead(
        conferenceId: String,
        channelId: String,
        networkInterfaceName: String,
        creationSignedBytesForEndpoint: ByteArray?,
    ): MembershipHeadMaterializationOutcome {
        if (hasPublishedSession(conferenceId)) {
            return when (
                syncSessionDeclarationToMembershipHead(
                    conferenceId = conferenceId,
                    channelId = channelId,
                    networkInterfaceName = networkInterfaceName,
                    creationSignedBytesForEndpoint = creationSignedBytesForEndpoint,
                )
            ) {
                SessionHeadSyncOutcome.APPLIED -> MembershipHeadMaterializationOutcome.APPLIED
                SessionHeadSyncOutcome.ALREADY_SYNCED -> MembershipHeadMaterializationOutcome.ALREADY_PRESENT
                SessionHeadSyncOutcome.SKIPPED_INITIAL_MEMBERSHIP ->
                    MembershipHeadMaterializationOutcome.SKIPPED_INITIAL_MEMBERSHIP
                SessionHeadSyncOutcome.NO_MEMBERSHIP -> MembershipHeadMaterializationOutcome.NO_MEMBERSHIP
                SessionHeadSyncOutcome.NO_SUPPLEMENT_REGISTRY ->
                    MembershipHeadMaterializationOutcome.NO_SUPPLEMENT_REGISTRY
                SessionHeadSyncOutcome.NO_SUPPLEMENT -> MembershipHeadMaterializationOutcome.NO_SUPPLEMENT
                SessionHeadSyncOutcome.NO_CREATION_CONTEXT ->
                    MembershipHeadMaterializationOutcome.NO_CREATION_CONTEXT
                SessionHeadSyncOutcome.REJECTED_INCOMPLETE,
                SessionHeadSyncOutcome.REJECTED_PUBLISH,
                -> MembershipHeadMaterializationOutcome.REJECTED_PUBLISH
            }
        }
        val generation =
            validator.membershipRegistry().current(conferenceId)
                ?: return MembershipHeadMaterializationOutcome.NO_MEMBERSHIP
        if (generation.membershipVersion == 0L) {
            return MembershipHeadMaterializationOutcome.SKIPPED_INITIAL_MEMBERSHIP
        }
        val registry = supplementRegistry
            ?: return MembershipHeadMaterializationOutcome.NO_SUPPLEMENT_REGISTRY
        val derived =
            registry.lookup(conferenceId, generation.mediaKeyEpoch)
                ?: return MembershipHeadMaterializationOutcome.NO_SUPPLEMENT
        val creationWire =
            decodeCreationWireForEndpoint(creationSignedBytesForEndpoint, channelId)
                ?: return MembershipHeadMaterializationOutcome.NO_CREATION_CONTEXT
        val declaration =
            AuthoritativeConferenceMediaSessionDeclaration(
                conferenceId = conferenceId,
                channelId = channelId,
                conferenceEpoch = generation.conferenceEpoch,
                membershipVersion = generation.membershipVersion,
                mediaKeyEpoch = generation.mediaKeyEpoch,
                endpoint = creationWire.endpoint,
                networkInterfaceName = networkInterfaceName,
                masterKey = derived.masterKey.copyOf(),
                masterSalt = derived.masterSalt.copyOf(),
                keyContextHint64 = derived.keyContextHint64.copyOf(),
                factGeneration = generation.conferenceEpoch * 1_000L + generation.membershipVersion,
                disposition = ConferenceMediaDeclarationDisposition.AUTHORITATIVE,
            )
        if (!ConferenceSessionMediaGbcPublisherBridge.validateSessionComplete(declaration)) {
            return MembershipHeadMaterializationOutcome.REJECTED_INCOMPLETE
        }
        validator.bootstrapSessionDeclaration(declaration)
        return when (publisherBridge.publishSessionDeclaration(declaration)) {
            ControlFactPublishOutcome.ACCEPTED,
            ControlFactPublishOutcome.IDEMPOTENT,
            -> MembershipHeadMaterializationOutcome.APPLIED
            else -> MembershipHeadMaterializationOutcome.REJECTED_PUBLISH
        }
    }

    /**
     * Host / local-loopback — republish session registry at converged membership head when
     * CREATION-era session declaration lags [Profile01MembershipGeneration.mediaKeyEpoch].
     */
    fun syncSessionDeclarationToMembershipHead(
        conferenceId: String,
        channelId: String,
        networkInterfaceName: String,
        creationSignedBytesForEndpoint: ByteArray?,
    ): SessionHeadSyncOutcome {
        val generation =
            validator.membershipRegistry().current(conferenceId)
                ?: return SessionHeadSyncOutcome.NO_MEMBERSHIP
        if (generation.membershipVersion == 0L) {
            return SessionHeadSyncOutcome.SKIPPED_INITIAL_MEMBERSHIP
        }
        val published = publisherBridge.publishedSession(conferenceId)
        val registry = supplementRegistry
            ?: return SessionHeadSyncOutcome.NO_SUPPLEMENT_REGISTRY
        val derived =
            registry.lookup(conferenceId, generation.mediaKeyEpoch)
                ?: return SessionHeadSyncOutcome.NO_SUPPLEMENT
        val publishedKeysMismatch =
            published != null &&
                (
                    !published.masterKey.contentEquals(derived.masterKey) ||
                        !published.masterSalt.contentEquals(derived.masterSalt) ||
                        !published.keyContextHint64.contentEquals(derived.keyContextHint64)
                )
        val needsSync =
            published == null ||
                published.mediaKeyEpoch < generation.mediaKeyEpoch ||
                published.membershipVersion < generation.membershipVersion ||
                publishedKeysMismatch
        if (!needsSync) {
            return SessionHeadSyncOutcome.ALREADY_SYNCED
        }
        val creationWire =
            decodeCreationWireForEndpoint(creationSignedBytesForEndpoint, channelId)
                ?: return SessionHeadSyncOutcome.NO_CREATION_CONTEXT
        val declaration =
            AuthoritativeConferenceMediaSessionDeclaration(
                conferenceId = conferenceId,
                channelId = channelId,
                conferenceEpoch = generation.conferenceEpoch,
                membershipVersion = generation.membershipVersion,
                mediaKeyEpoch = generation.mediaKeyEpoch,
                endpoint = creationWire.endpoint,
                networkInterfaceName = networkInterfaceName,
                masterKey = derived.masterKey.copyOf(),
                masterSalt = derived.masterSalt.copyOf(),
                keyContextHint64 = derived.keyContextHint64.copyOf(),
                factGeneration = generation.conferenceEpoch * 1_000L + generation.membershipVersion,
                disposition = ConferenceMediaDeclarationDisposition.AUTHORITATIVE,
            )
        if (!ConferenceSessionMediaGbcPublisherBridge.validateSessionComplete(declaration)) {
            return SessionHeadSyncOutcome.REJECTED_INCOMPLETE
        }
        validator.bootstrapSessionDeclaration(declaration)
        return when (publisherBridge.publishSessionDeclaration(declaration)) {
            ControlFactPublishOutcome.ACCEPTED,
            ControlFactPublishOutcome.IDEMPOTENT,
            -> SessionHeadSyncOutcome.APPLIED
            else -> SessionHeadSyncOutcome.REJECTED_PUBLISH
        }
    }

    private fun decodeCreationWireForEndpoint(
        creationSignedBytesForEndpoint: ByteArray?,
        channelId: String,
    ): Profile01WireSessionFact? {
        val signedBytes = creationSignedBytesForEndpoint ?: return null
        val placeholder =
            Profile01SessionMediaSupplement(
                channelId = channelId,
                masterKey = ByteArray(16),
                masterSalt = ByteArray(12),
                keyContextHint64 = ByteArray(8),
            )
        return when (val decoded = Profile01WireCborDecoder.decodeCreationSession(signedBytes, placeholder)) {
            is Profile01WireCborDecoder.DecodeResult.Ready -> decoded.value
            is Profile01WireCborDecoder.DecodeResult.Rejected -> null
        }
    }

    fun ingestCreationSignedFact(
        signedFactBytes: ByteArray,
        supplement: Profile01SessionMediaSupplement,
        networkInterfaceName: String,
    ): Profile01SignedFactIngressResult {
        val envelope =
            Profile01WireCborDecoder.parseEnvelope(signedFactBytes)
                ?: return rejectDecode("MALFORMED_SIGNED_FACT")
        val fullFact =
            runCatching {
                com.talkback.core.conference.session.profile01.wire.Profile01CborCodec.decodeStrict(
                    envelope.fullCanonicalBytes,
                )
            }.getOrElse {
                return rejectDecode("MALFORMED_FULL_FACT")
            }
        val map = fullFact.intKeyMap()
        val authority = map?.get(2)?.intKeyMap()
        val conferenceIdHex =
            authority?.get(0)?.asByteString()?.let { bytes ->
                if (bytes.size == 16) {
                    bytes.joinToString(separator = "") { byte ->
                        "%02x".format(byte.toInt() and 0xFF)
                    }
                } else {
                    null
                }
            }
        val mediaKeyEpoch = authority?.get(6)?.asUnsigned()
        val resolvedSupplement =
            if (conferenceIdHex != null && mediaKeyEpoch != null && supplementRegistry != null) {
                supplementRegistry.resolveSupplement(
                    conferenceId = conferenceIdHex,
                    mediaKeyEpoch = mediaKeyEpoch,
                    channelId = supplement.channelId,
                    fallback = supplement,
                )
            } else {
                supplement
            }
        return when (
            val decoded =
                Profile01WireCborDecoder.decodeCreationSession(signedFactBytes, resolvedSupplement)
        ) {
            is Profile01WireCborDecoder.DecodeResult.Rejected ->
                Profile01SignedFactIngressResult(
                    decode = Profile01SignedFactDecodeResult.Rejected(decoded.reason),
                    ingress = null,
                )
            is Profile01WireCborDecoder.DecodeResult.Ready ->
                Profile01SignedFactIngressResult(
                    decode = Profile01SignedFactDecodeResult.Ready,
                    ingress = ingestSession(decoded.value, networkInterfaceName),
                )
        }
    }

    fun ingestMediaKeyPackageSignedFact(
        signedFactBytes: ByteArray,
        localRecipientModuleId: String,
        localEstablishmentKeyVersion: Long,
    ): Profile01MediaKeyPackageIngressResult {
        val decryptSeam = mediaKeyDecrypt
        if (decryptSeam == null) {
            return rejectMediaKeyPackage(
                reason = "DECRYPT_SEAM_UNAVAILABLE",
                conferenceId = "-",
                mediaKeyEpoch = "-",
                recipientModuleId = localRecipientModuleId,
                establishmentKeyVersion = localEstablishmentKeyVersion,
            )
        }
        val registry = supplementRegistry
        if (registry == null) {
            return rejectMediaKeyPackage(
                reason = "SUPPLEMENT_REGISTRY_UNAVAILABLE",
                conferenceId = "-",
                mediaKeyEpoch = "-",
                recipientModuleId = localRecipientModuleId,
                establishmentKeyVersion = localEstablishmentKeyVersion,
            )
        }
        return when (val decoded = Profile01WireCborDecoder.decodeMediaKeyPackage(signedFactBytes)) {
            is Profile01WireCborDecoder.DecodeResult.Rejected ->
                rejectMediaKeyPackage(
                    reason = decoded.reason,
                    conferenceId = "-",
                    mediaKeyEpoch = "-",
                    recipientModuleId = localRecipientModuleId,
                    establishmentKeyVersion = localEstablishmentKeyVersion,
                )
            is Profile01WireCborDecoder.DecodeResult.Ready -> {
                val wire = decoded.value
                val conferenceIdHex = wire.conferenceId.toHexLower()
                when (val verified = validator.verifyMediaKeyPackage(wire)) {
                    is Profile01WireVerificationResult.Rejected ->
                        return rejectMediaKeyPackage(
                            reason = verified.reason,
                            conferenceId = conferenceIdHex,
                            mediaKeyEpoch = wire.mediaKeyEpoch.toString(),
                            recipientModuleId = wire.recipientModuleId,
                            establishmentKeyVersion = localEstablishmentKeyVersion,
                        )
                    is Profile01WireVerificationResult.Verified -> {
                        if (!verified.factDigest.contentEquals(wire.factDigest)) {
                            return rejectMediaKeyPackage(
                                reason = "FACT_DIGEST_MISMATCH",
                                conferenceId = conferenceIdHex,
                                mediaKeyEpoch = wire.mediaKeyEpoch.toString(),
                                recipientModuleId = wire.recipientModuleId,
                                establishmentKeyVersion = localEstablishmentKeyVersion,
                            )
                        }
                    }
                }
                when (
                    val decrypted =
                        decryptSeam.decrypt(
                            wire,
                            localRecipientModuleId,
                            localEstablishmentKeyVersion,
                        )
                ) {
                    is Profile01MediaKeyPackageDecryptResult.Rejected ->
                        rejectMediaKeyPackage(
                            reason = decrypted.reason,
                            conferenceId = conferenceIdHex,
                            mediaKeyEpoch = wire.mediaKeyEpoch.toString(),
                            recipientModuleId = wire.recipientModuleId,
                            establishmentKeyVersion = localEstablishmentKeyVersion,
                        )
                    is Profile01MediaKeyPackageDecryptResult.Ready -> {
                        registry.put(decrypted.material)
                        // C-F6: READY only after registry side effect — not merely decrypt return.
                        notifySupplementReady(
                            conferenceId = decrypted.material.conferenceId,
                            mediaKeyEpoch = decrypted.material.mediaKeyEpoch,
                        )
                        logMediaKeyIngest(
                            conferenceId = decrypted.material.conferenceId,
                            mediaKeyEpoch = decrypted.material.mediaKeyEpoch.toString(),
                            recipientModuleId = wire.recipientModuleId,
                            establishmentKeyVersion = localEstablishmentKeyVersion,
                            outcome = "APPLIED",
                        )
                        Profile01MediaKeyPackageIngressResult.Ready(decrypted.material)
                    }
                }
            }
        }
    }

    /** P1-C-FIELD-OBS — ingest outcome; never logs key/salt/PEK/plaintext. */
    private fun rejectMediaKeyPackage(
        reason: String,
        conferenceId: String,
        mediaKeyEpoch: String,
        recipientModuleId: String,
        establishmentKeyVersion: Long,
    ): Profile01MediaKeyPackageIngressResult.Rejected {
        logMediaKeyIngest(
            conferenceId = conferenceId,
            mediaKeyEpoch = mediaKeyEpoch,
            recipientModuleId = recipientModuleId,
            establishmentKeyVersion = establishmentKeyVersion,
            outcome = "REJECTED_$reason",
        )
        return Profile01MediaKeyPackageIngressResult.Rejected(reason)
    }

    private fun logMediaKeyIngest(
        conferenceId: String,
        mediaKeyEpoch: String,
        recipientModuleId: String,
        establishmentKeyVersion: Long,
        outcome: String,
    ) {
        onLog(
            "PROFILE01_MEDIA_KEY_INGEST " +
                "conferenceId=$conferenceId " +
                "mediaKeyEpoch=$mediaKeyEpoch " +
                "recipientModuleId=$recipientModuleId " +
                "establishmentKeyVersion=$establishmentKeyVersion " +
                "outcome=$outcome",
        )
    }

    /** Shared supplement-ready notification for peer decrypt and host local materialization (TX-A1). */
    fun notifySupplementReady(
        conferenceId: String,
        mediaKeyEpoch: Long,
    ) {
        logMediaSupplementReady(conferenceId, mediaKeyEpoch)
        onMediaSupplementReady?.invoke(conferenceId, mediaKeyEpoch)
    }

    private fun logMediaSupplementReady(
        conferenceId: String,
        mediaKeyEpoch: Long,
    ) {
        onLog(
            "PROFILE01_MEDIA_SUPPLEMENT " +
                "conferenceId=$conferenceId " +
                "mediaKeyEpoch=$mediaKeyEpoch " +
                "state=READY",
        )
    }

    fun ingestMembershipSignedFact(
        signedFactBytes: ByteArray,
    ): Profile01MembershipIngressResult {
        return when (val decoded = Profile01WireCborDecoder.decodeMembership(signedFactBytes)) {
            is Profile01WireCborDecoder.DecodeResult.Rejected ->
                Profile01MembershipIngressResult.Rejected(decoded.reason)
            is Profile01WireCborDecoder.DecodeResult.Ready ->
                when (val validation = validator.validateMembership(decoded.value)) {
                    is Profile01ValidationResult.ReadyMembership -> {
                        validation.sessionRepublication?.let { republication ->
                            publisherBridge.publishSessionDeclaration(republication)
                        }
                        Profile01MembershipIngressResult.Converged(validation.generation)
                    }
                    is Profile01ValidationResult.NotPublished ->
                        Profile01MembershipIngressResult.Pending(validation.reason)
                    is Profile01ValidationResult.Invalid ->
                        Profile01MembershipIngressResult.Rejected(validation.reason)
                    else ->
                        Profile01MembershipIngressResult.Rejected("UNEXPECTED_VALIDATION_OUTCOME")
                }
        }
    }

    fun ingestSourceDeclarationSignedFact(
        signedFactBytes: ByteArray,
    ): Profile01SignedFactIngressResult {
        return when (val decoded = Profile01WireCborDecoder.decodeSourceDeclarationMember(signedFactBytes)) {
            is Profile01WireCborDecoder.DecodeResult.Rejected ->
                Profile01SignedFactIngressResult(
                    decode = Profile01SignedFactDecodeResult.Rejected(decoded.reason),
                    ingress = null,
                )
            is Profile01WireCborDecoder.DecodeResult.Ready ->
                Profile01SignedFactIngressResult(
                    decode = Profile01SignedFactDecodeResult.Ready,
                    ingress = ingestMember(decoded.value),
                )
        }
    }

    fun ingestSession(
        wire: Profile01WireSessionFact,
        networkInterfaceName: String,
    ): Profile01IngressResult {
        val validation = validator.validateSession(wire, networkInterfaceName)
        val publishOutcome =
            when (validation) {
                is Profile01ValidationResult.ReadySession -> {
                    val primary = publisherBridge.publishSessionDeclaration(validation.declaration)
                    validation.membershipRepublication?.let { republication ->
                        publisherBridge.publishSessionDeclaration(republication)
                    }
                    onCreationMembershipEstablished?.invoke(wire.conferenceId)
                    primary
                }
                else -> null
            }
        return Profile01IngressResult(validation = validation, publishOutcome = publishOutcome)
    }

    fun ingestMember(wire: Profile01WireMemberSourceFact): Profile01IngressResult {
        val validation = validator.validateMember(wire)
        val publishOutcome =
            when (validation) {
                is Profile01ValidationResult.ReadyMember ->
                    publisherBridge.publishMemberDeclaration(validation.declaration)
                else -> null
            }
        return Profile01IngressResult(validation = validation, publishOutcome = publishOutcome)
    }

    private fun rejectDecode(reason: String): Profile01SignedFactIngressResult =
        Profile01SignedFactIngressResult(
            decode = Profile01SignedFactDecodeResult.Rejected(reason),
            ingress = null,
        )
}

data class Profile01IngressResult(
    val validation: Profile01ValidationResult,
    val publishOutcome: ControlFactPublishOutcome?,
)

sealed class Profile01SignedFactDecodeResult {
    data object Ready : Profile01SignedFactDecodeResult()

    data class Rejected(val reason: String) : Profile01SignedFactDecodeResult()
}

data class Profile01SignedFactIngressResult(
    val decode: Profile01SignedFactDecodeResult,
    val ingress: Profile01IngressResult?,
)

sealed class Profile01MediaKeyPackageIngressResult {
    data class Ready(
        val material: com.talkback.core.conference.session.profile01.wire.Profile01DerivedMediaKeyMaterial,
    ) : Profile01MediaKeyPackageIngressResult()

    data class Rejected(val reason: String) : Profile01MediaKeyPackageIngressResult()
}

sealed class Profile01MembershipIngressResult {
    data class Converged(val generation: Profile01MembershipGeneration) : Profile01MembershipIngressResult()

    data class Pending(val reason: String) : Profile01MembershipIngressResult()

    data class Rejected(val reason: String) : Profile01MembershipIngressResult()
}

enum class MembershipHeadMaterializationOutcome {
    APPLIED,
    ALREADY_PRESENT,
    NO_MEMBERSHIP,
    SKIPPED_INITIAL_MEMBERSHIP,
    NO_SUPPLEMENT_REGISTRY,
    NO_SUPPLEMENT,
    NO_CREATION_CONTEXT,
    REJECTED_INCOMPLETE,
    REJECTED_PUBLISH,
}

enum class SessionHeadSyncOutcome {
    APPLIED,
    ALREADY_SYNCED,
    NO_MEMBERSHIP,
    SKIPPED_INITIAL_MEMBERSHIP,
    NO_SUPPLEMENT_REGISTRY,
    NO_SUPPLEMENT,
    NO_CREATION_CONTEXT,
    REJECTED_INCOMPLETE,
    REJECTED_PUBLISH,
}
