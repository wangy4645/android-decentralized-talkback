package com.talkback.core.conference.session.profile01.wire

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Verified MEDIA_KEY_PACKAGE → SRTP key material.
 *
 * Does not decide membership/generation policy or touch Wiring.
 */
class Profile01MediaKeyPackageDecryptSeam(
    private val recipientKeys: Profile01RecipientKeyEstablishmentSeam,
) {
    fun decrypt(
        packageWire: Profile01WireMediaKeyPackage,
        localRecipientModuleId: String,
        localEstablishmentKeyVersion: Long,
    ): Profile01MediaKeyPackageDecryptResult {
        if (packageWire.recipientModuleId != localRecipientModuleId) {
            return Profile01MediaKeyPackageDecryptResult.Rejected("RECIPIENT_MODULE_MISMATCH")
        }
        if (packageWire.recipientKeyVersion != localEstablishmentKeyVersion) {
            return Profile01MediaKeyPackageDecryptResult.Rejected("RECIPIENT_KEY_VERSION_MISMATCH")
        }
        if (packageWire.wrappedPek.size != Profile01WireConstants.WRAPPED_PEK_BYTES) {
            return Profile01MediaKeyPackageDecryptResult.Rejected("INVALID_WRAPPED_PEK")
        }
        if (packageWire.gcmNonce.size != Profile01WireConstants.GCM_NONCE_BYTES) {
            return Profile01MediaKeyPackageDecryptResult.Rejected("INVALID_GCM_NONCE")
        }
        if (packageWire.gcmTag.size != Profile01WireConstants.GCM_TAG_BYTES) {
            return Profile01MediaKeyPackageDecryptResult.Rejected("INVALID_GCM_TAG")
        }
        val pek =
            when (
                val unwrap =
                    recipientKeys.unwrapPek(
                        packageWire.wrappedPek,
                        localRecipientModuleId,
                        localEstablishmentKeyVersion,
                    )
            ) {
                is Profile01PekUnwrapResult.Rejected ->
                    return Profile01MediaKeyPackageDecryptResult.Rejected(unwrap.reason)
                is Profile01PekUnwrapResult.Ready -> unwrap.pek
            }
        val plaintext =
            runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    javax.crypto.spec.SecretKeySpec(pek, "AES"),
                    GCMParameterSpec(128, packageWire.gcmNonce),
                )
                cipher.updateAAD(Profile01PackageCrypto.packageAadBytes(packageWire))
                cipher.doFinal(packageWire.ciphertext + packageWire.gcmTag)
            }.getOrElse {
                return Profile01MediaKeyPackageDecryptResult.Rejected("GCM_AUTH_FAIL")
            }
        val parsed =
            Profile01PackageCrypto.parsePackagePlaintext(plaintext)
                ?: return Profile01MediaKeyPackageDecryptResult.Rejected("MALFORMED_PACKAGE_PLAINTEXT")
        if (!Profile01PackageCrypto.verifyMediaKeyCommitment(
                parsed.conferenceMediaSecret,
                parsed.membershipKeyContextDigest,
                packageWire.mediaKeyCommitment,
            )
        ) {
            return Profile01MediaKeyPackageDecryptResult.Rejected("COMMITMENT_FAIL")
        }
        val srtp =
            Profile01PackageCrypto.deriveSrtpMaterial(
                parsed.conferenceMediaSecret,
                parsed.membershipKeyContextDigest,
            )
        val hint =
            Profile01PackageCrypto.deriveKeyContextHint64(
                packageWire.conferenceId,
                packageWire.conferenceEpoch,
                packageWire.mediaKeyEpoch,
                parsed.membershipKeyContextDigest,
            )
        return Profile01MediaKeyPackageDecryptResult.Ready(
            Profile01DerivedMediaKeyMaterial(
                conferenceId = packageWire.conferenceId.toHex(),
                conferenceEpoch = packageWire.conferenceEpoch,
                membershipVersion = packageWire.membershipVersion,
                mediaKeyEpoch = packageWire.mediaKeyEpoch,
                masterKey = srtp.masterKey,
                masterSalt = srtp.masterSalt,
                keyContextHint64 = hint,
                membershipKeyContextDigest = parsed.membershipKeyContextDigest,
            ),
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
}

sealed class Profile01MediaKeyPackageDecryptResult {
    data class Ready(val material: Profile01DerivedMediaKeyMaterial) : Profile01MediaKeyPackageDecryptResult()

    data class Rejected(val reason: String) : Profile01MediaKeyPackageDecryptResult()
}
