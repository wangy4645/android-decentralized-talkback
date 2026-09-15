package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.session.gbc.trust.profile.establishment.ModuleEstablishmentBinding
import java.security.SecureRandom

/**
 * Conference media key material for MEDIA_KEY_PACKAGE build (P1-B).
 */
data class Profile01ConferenceKeyMaterial(
    val conferenceId: ByteArray,
    val conferenceEpoch: Long,
    val ownerModuleId: String,
    val membershipVersion: Long,
    val mediaKeyEpoch: Long,
    val conferenceMediaSecret: ByteArray,
    val membershipKeyContextDigest: ByteArray,
    val mediaKeyCommitment: ByteArray,
)

/**
 * Authoritative recipient input — builder does not lookup establishment keys.
 */
data class Profile01PackageRecipientBinding(
    val recipientModuleId: String,
    val establishmentKeyVersion: Long,
    val establishmentPublicKeySpki: ByteArray,
) {
    companion object {
        fun fromModuleEstablishmentBinding(
            binding: ModuleEstablishmentBinding,
        ): Profile01PackageRecipientBinding =
            Profile01PackageRecipientBinding(
                recipientModuleId = binding.moduleId,
                establishmentKeyVersion = binding.establishmentKeyVersion,
                establishmentPublicKeySpki = binding.establishmentPublicKeySpki.copyOf(),
            )
    }
}

data class Profile01MediaKeyPackageBuildRequest(
    val material: Profile01ConferenceKeyMaterial,
    val creationFactDigest: ByteArray,
    val recipient: Profile01PackageRecipientBinding,
)

sealed class Profile01MediaKeyPackageBuildResult {
    data class Ready(
        val signedFactBytes: ByteArray,
        val packageIdentity: ByteArray,
    ) : Profile01MediaKeyPackageBuildResult()

    data class Rejected(val reason: String) : Profile01MediaKeyPackageBuildResult()
}

fun interface Profile01PackageRandomSource {
    fun nextBytes(size: Int): ByteArray

    companion object {
        val SecureRandom: Profile01PackageRandomSource =
            Profile01PackageRandomSource { size ->
                ByteArray(size).also { SecureRandom().nextBytes(it) }
            }

        fun fixed(
            pek: ByteArray,
            packageIdentity: ByteArray,
            gcmNonce: ByteArray,
        ): Profile01PackageRandomSource {
            val queue =
                mutableListOf(
                    pek.copyOf(),
                    packageIdentity.copyOf(),
                    gcmNonce.copyOf(),
                )
            return Profile01PackageRandomSource { size ->
                val next = queue.removeFirstOrNull()
                    ?: error("fixed random source exhausted (requested $size bytes)")
                require(next.size == size) {
                    "fixed random chunk size ${next.size} != requested $size"
                }
                next.copyOf()
            }
        }
    }
}

/**
 * P1-B MEDIA_KEY_PACKAGE builder — artifact only; no send/publication side effects.
 */
class Profile01MediaKeyPackageBuilder(
    private val signer: Profile01SignedFactSigner,
    private val randomSource: Profile01PackageRandomSource = Profile01PackageRandomSource.SecureRandom,
) {
    fun build(request: Profile01MediaKeyPackageBuildRequest): Profile01MediaKeyPackageBuildResult {
        val material = request.material
        if (material.ownerModuleId != signer.signerModuleId) {
            return reject("SIGNER_OWNER_MISMATCH")
        }
        if (material.conferenceId.size != 16) return reject("INVALID_CONFERENCE_ID")
        if (material.conferenceMediaSecret.size != Profile01WireConstants.CONFERENCE_MEDIA_SECRET_BYTES) {
            return reject("INVALID_CONFERENCE_MEDIA_SECRET")
        }
        if (material.membershipKeyContextDigest.size != 32) return reject("INVALID_MEMBERSHIP_CONTEXT_DIGEST")
        if (material.mediaKeyCommitment.size != 32) return reject("INVALID_MEDIA_KEY_COMMITMENT")
        if (request.creationFactDigest.size != 32) return reject("INVALID_CREATION_FACT_DIGEST")
        if (request.recipient.recipientModuleId.isBlank()) return reject("INVALID_RECIPIENT_MODULE")
        if (request.recipient.establishmentPublicKeySpki.isEmpty()) {
            return reject("INVALID_ESTABLISHMENT_PUBLIC_KEY")
        }
        if (!Profile01PackageCrypto.verifyMediaKeyCommitment(
                material.conferenceMediaSecret,
                material.membershipKeyContextDigest,
                material.mediaKeyCommitment,
            )
        ) {
            return reject("COMMITMENT_MISMATCH")
        }

        val pek = randomSource.nextBytes(32)
        val packageIdentity = randomSource.nextBytes(16)
        val gcmNonce = randomSource.nextBytes(Profile01WireConstants.GCM_NONCE_BYTES)

        val wrappedPek =
            when (
                val wrap =
                    Profile01PackageCrypto.wrapPekWithEstablishmentPublicKey(
                        pek,
                        request.recipient.establishmentPublicKeySpki,
                    )
            ) {
                is Profile01PackageCrypto.WrapPekResult.Ready -> wrap.wrappedPek
                is Profile01PackageCrypto.WrapPekResult.Rejected -> return reject(wrap.reason)
            }

        val plaintext =
            Profile01PackageCrypto.encodePackagePlaintext(
                conferenceMediaSecret = material.conferenceMediaSecret,
                membershipKeyContextDigest = material.membershipKeyContextDigest,
            )

        val wireForAad =
            Profile01WireMediaKeyPackage(
                signedFactBytes = byteArrayOf(),
                factDigest = byteArrayOf(),
                fullCanonicalBytes = byteArrayOf(),
                conferenceId = material.conferenceId.copyOf(),
                conferenceEpoch = material.conferenceEpoch,
                ownerModuleId = material.ownerModuleId,
                membershipVersion = material.membershipVersion,
                mediaKeyEpoch = material.mediaKeyEpoch,
                membershipFactDigest = request.creationFactDigest.copyOf(),
                mediaKeyCommitment = material.mediaKeyCommitment.copyOf(),
                recipientModuleId = request.recipient.recipientModuleId,
                packageIdentity = packageIdentity.copyOf(),
                wrappedPek = wrappedPek.copyOf(),
                gcmNonce = gcmNonce.copyOf(),
                ciphertext = byteArrayOf(),
                gcmTag = ByteArray(Profile01WireConstants.GCM_TAG_BYTES),
                recipientKeyVersion = request.recipient.establishmentKeyVersion,
            )
        val aad = Profile01PackageCrypto.packageAadBytes(wireForAad)

        val encrypted =
            when (val enc = Profile01PackageCrypto.encryptPackagePayload(plaintext, pek, gcmNonce, aad)) {
                is Profile01PackageCrypto.EncryptPackageResult.Ready -> enc
                is Profile01PackageCrypto.EncryptPackageResult.Rejected -> return reject(enc.reason)
            }

        val authoritySnapshot =
            Profile01MediaKeyPackageAuthoritySnapshot(
                conferenceId = material.conferenceId.copyOf(),
                conferenceEpoch = material.conferenceEpoch,
                ownerModuleId = material.ownerModuleId,
                membershipVersion = material.membershipVersion,
                mediaKeyEpoch = material.mediaKeyEpoch,
                membershipFactDigest = request.creationFactDigest.copyOf(),
                mediaKeyCommitment = material.mediaKeyCommitment.copyOf(),
                recipientModuleId = request.recipient.recipientModuleId,
                packageIdentity = packageIdentity.copyOf(),
                wrappedPek = wrappedPek.copyOf(),
                gcmNonce = gcmNonce.copyOf(),
                ciphertext = encrypted.ciphertext.copyOf(),
                gcmTag = encrypted.gcmTag.copyOf(),
                recipientKeyVersion = request.recipient.establishmentKeyVersion,
                signerKeyVersion = signer.signerKeyVersion,
            )
        val fullCanonicalBytes = Profile01WireCborEncoder.encodeMediaKeyPackageFullFact(authoritySnapshot)
        val signature = signer.signFullFact(fullCanonicalBytes)
        val signedFactBytes = Profile01SignedFactEnvelopeBuilder.build(fullCanonicalBytes, signature)
        return Profile01MediaKeyPackageBuildResult.Ready(
            signedFactBytes = signedFactBytes.copyOf(),
            packageIdentity = packageIdentity.copyOf(),
        )
    }

    /**
     * Field-test C-F7 seam: start from a normally built package, overwrite **only**
     * [Profile01WireMediaKeyPackage.recipientKeyVersion], then re-encode + re-sign with
     * this builder's signer.
     *
     * Does not re-wrap PEK, does not mutate ciphertext, does not touch establishment
     * trust/Keystore. Decrypt rejects at RECIPIENT_KEY_VERSION_MISMATCH before GCM.
     */
    fun overrideRecipientKeyVersionAndResign(
        signedFactBytes: ByteArray,
        wireRecipientKeyVersion: Long,
    ): Profile01MediaKeyPackageBuildResult {
        val decoded =
            when (val d = Profile01WireCborDecoder.decodeMediaKeyPackage(signedFactBytes)) {
                is Profile01WireCborDecoder.DecodeResult.Ready -> d.value
                is Profile01WireCborDecoder.DecodeResult.Rejected -> return reject("OVERRIDE_DECODE_${d.reason}")
            }
        if (decoded.recipientKeyVersion == wireRecipientKeyVersion) {
            return reject("OVERRIDE_VERSION_NOT_DISTINCT")
        }
        val snapshot =
            Profile01MediaKeyPackageAuthoritySnapshot(
                conferenceId = decoded.conferenceId.copyOf(),
                conferenceEpoch = decoded.conferenceEpoch,
                ownerModuleId = decoded.ownerModuleId,
                membershipVersion = decoded.membershipVersion,
                mediaKeyEpoch = decoded.mediaKeyEpoch,
                membershipFactDigest = decoded.membershipFactDigest.copyOf(),
                mediaKeyCommitment = decoded.mediaKeyCommitment.copyOf(),
                recipientModuleId = decoded.recipientModuleId,
                packageIdentity = decoded.packageIdentity.copyOf(),
                wrappedPek = decoded.wrappedPek.copyOf(),
                gcmNonce = decoded.gcmNonce.copyOf(),
                ciphertext = decoded.ciphertext.copyOf(),
                gcmTag = decoded.gcmTag.copyOf(),
                recipientKeyVersion = wireRecipientKeyVersion,
                signerKeyVersion = signer.signerKeyVersion,
            )
        val fullCanonicalBytes = Profile01WireCborEncoder.encodeMediaKeyPackageFullFact(snapshot)
        val signature = signer.signFullFact(fullCanonicalBytes)
        val overridden = Profile01SignedFactEnvelopeBuilder.build(fullCanonicalBytes, signature)
        return Profile01MediaKeyPackageBuildResult.Ready(
            signedFactBytes = overridden.copyOf(),
            packageIdentity = decoded.packageIdentity.copyOf(),
        )
    }

    private fun reject(reason: String): Profile01MediaKeyPackageBuildResult.Rejected =
        Profile01MediaKeyPackageBuildResult.Rejected(reason)
}
