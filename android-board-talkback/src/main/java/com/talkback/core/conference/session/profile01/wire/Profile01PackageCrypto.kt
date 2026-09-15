package com.talkback.core.conference.session.profile01.wire

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Profile01PackageCrypto {
    fun packageAadBytes(packageWire: Profile01WireMediaKeyPackage): ByteArray {
        val core =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    Profile01CborCodec.CborValue.Unsigned(0) to
                        Profile01CborCodec.CborValue.ByteString(packageWire.conferenceId),
                    Profile01CborCodec.CborValue.Unsigned(1) to
                        Profile01CborCodec.CborValue.Unsigned(packageWire.conferenceEpoch),
                    Profile01CborCodec.CborValue.Unsigned(2) to
                        Profile01CborCodec.CborValue.Text(packageWire.ownerModuleId),
                    Profile01CborCodec.CborValue.Unsigned(3) to
                        Profile01CborCodec.CborValue.Unsigned(packageWire.membershipVersion),
                    Profile01CborCodec.CborValue.Unsigned(4) to
                        Profile01CborCodec.CborValue.Unsigned(packageWire.mediaKeyEpoch),
                    Profile01CborCodec.CborValue.Unsigned(5) to
                        Profile01CborCodec.CborValue.ByteString(packageWire.membershipFactDigest),
                    Profile01CborCodec.CborValue.Unsigned(6) to
                        Profile01CborCodec.CborValue.ByteString(packageWire.mediaKeyCommitment),
                    Profile01CborCodec.CborValue.Unsigned(7) to
                        Profile01CborCodec.CborValue.Text(packageWire.recipientModuleId),
                    Profile01CborCodec.CborValue.Unsigned(8) to
                        Profile01CborCodec.CborValue.ByteString(packageWire.packageIdentity),
                    Profile01CborCodec.CborValue.Unsigned(9) to
                        Profile01CborCodec.CborValue.ByteString(packageWire.wrappedPek),
                    Profile01CborCodec.CborValue.Unsigned(10) to
                        Profile01CborCodec.CborValue.ByteString(packageWire.gcmNonce),
                    Profile01CborCodec.CborValue.Unsigned(11) to
                        Profile01CborCodec.CborValue.Unsigned(packageWire.recipientKeyVersion),
                ),
            )
        val envelope =
            Profile01CborCodec.CborValue.CborArray(
                listOf(
                    Profile01CborCodec.CborValue.Unsigned(Profile01WireConstants.SCHEMA_VERSION.toLong()),
                    Profile01CborCodec.CborValue.Unsigned(Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE.toLong()),
                    core,
                ),
            )
        return Profile01WireConstants.PACKAGE_AAD_DOMAIN + Profile01CborCodec.encode(envelope)
    }

    fun parsePackagePlaintext(plaintext: ByteArray): PackagePlaintext? {
        return runCatching {
            val map =
                Profile01CborCodec.decodeStrict(plaintext).intKeyMap()
                    ?: return null
            val version = map[0]?.asUnsigned()?.toInt() ?: return null
            if (version != 1) return null
            val secret = map[1]?.asByteString() ?: return null
            val contextDigest = map[2]?.asByteString() ?: return null
            if (secret.size != Profile01WireConstants.CONFERENCE_MEDIA_SECRET_BYTES ||
                contextDigest.size != 32
            ) {
                return null
            }
            PackagePlaintext(
                conferenceMediaSecret = secret.copyOf(),
                membershipKeyContextDigest = contextDigest.copyOf(),
            )
        }.getOrNull()
    }

    fun computeMediaKeyCommitment(
        conferenceMediaSecret: ByteArray,
        membershipKeyContextDigest: ByteArray,
    ): ByteArray =
        hmacSha256(
            conferenceMediaSecret,
            Profile01WireConstants.MEDIA_KEY_COMMITMENT_DOMAIN + membershipKeyContextDigest,
        )

    fun verifyMediaKeyCommitment(
        conferenceMediaSecret: ByteArray,
        membershipKeyContextDigest: ByteArray,
        expectedCommitment: ByteArray,
    ): Boolean {
        val actual = computeMediaKeyCommitment(conferenceMediaSecret, membershipKeyContextDigest)
        return actual.contentEquals(expectedCommitment)
    }

    fun deriveSrtpMaterial(
        conferenceMediaSecret: ByteArray,
        membershipKeyContextDigest: ByteArray,
    ): SrtpKeyMaterial {
        val prk = hmacSha256(membershipKeyContextDigest, conferenceMediaSecret)
        val material =
            hkdfExpandSha256(
                prk,
                Profile01WireConstants.SRTP_KEY_INFO_DOMAIN + membershipKeyContextDigest,
                28,
            )
        return SrtpKeyMaterial(
            masterKey = material.copyOfRange(0, 16),
            masterSalt = material.copyOfRange(16, 28),
        )
    }

    fun deriveKeyContextHint64(
        conferenceId: ByteArray,
        conferenceEpoch: Long,
        mediaKeyEpoch: Long,
        membershipKeyContextDigest: ByteArray,
    ): ByteArray {
        require(conferenceId.size == 16) { "conferenceId must be 16 bytes" }
        require(membershipKeyContextDigest.size == 32) { "context digest must be 32 bytes" }
        val input =
            Profile01WireConstants.RTP_KEY_CONTEXT_HINT_DOMAIN +
                conferenceId +
                conferenceEpoch.u64Be() +
                mediaKeyEpoch.u64Be() +
                membershipKeyContextDigest
        return MessageDigest.getInstance("SHA-256").digest(input).copyOfRange(0, 8)
    }

    fun encodePackagePlaintext(
        conferenceMediaSecret: ByteArray,
        membershipKeyContextDigest: ByteArray,
    ): ByteArray {
        require(conferenceMediaSecret.size == Profile01WireConstants.CONFERENCE_MEDIA_SECRET_BYTES) {
            "invalid conferenceMediaSecret"
        }
        require(membershipKeyContextDigest.size == 32) { "invalid membershipKeyContextDigest" }
        val plaintext =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    Profile01CborCodec.CborValue.Unsigned(0) to Profile01CborCodec.CborValue.Unsigned(1),
                    Profile01CborCodec.CborValue.Unsigned(1) to
                        Profile01CborCodec.CborValue.ByteString(conferenceMediaSecret.copyOf()),
                    Profile01CborCodec.CborValue.Unsigned(2) to
                        Profile01CborCodec.CborValue.ByteString(membershipKeyContextDigest.copyOf()),
                ),
            )
        return Profile01CborCodec.encode(plaintext)
    }

    sealed class WrapPekResult {
        data class Ready(val wrappedPek: ByteArray) : WrapPekResult()

        data class Rejected(val reason: String) : WrapPekResult()
    }

    fun wrapPekWithEstablishmentPublicKey(
        pek: ByteArray,
        establishmentPublicKeySpki: ByteArray,
    ): WrapPekResult {
        if (pek.size != 32) return WrapPekResult.Rejected("INVALID_PEK")
        return runCatching {
            val publicKey =
                java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(java.security.spec.X509EncodedKeySpec(establishmentPublicKeySpki))
            val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            val spec =
                javax.crypto.spec.OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    java.security.spec.MGF1ParameterSpec.SHA1,
                    javax.crypto.spec.PSource.PSpecified.DEFAULT,
                )
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, publicKey, spec)
            val wrapped = cipher.doFinal(pek)
            if (wrapped.size != Profile01WireConstants.WRAPPED_PEK_BYTES) {
                return WrapPekResult.Rejected("INVALID_WRAPPED_PEK_SIZE")
            }
            WrapPekResult.Ready(wrapped.copyOf())
        }.getOrElse {
            WrapPekResult.Rejected("OAEP_ENCRYPT_FAIL")
        }
    }

    sealed class EncryptPackageResult {
        data class Ready(
            val ciphertext: ByteArray,
            val gcmTag: ByteArray,
        ) : EncryptPackageResult()

        data class Rejected(val reason: String) : EncryptPackageResult()
    }

    fun encryptPackagePayload(
        plaintext: ByteArray,
        pek: ByteArray,
        gcmNonce: ByteArray,
        aad: ByteArray,
    ): EncryptPackageResult {
        if (pek.size != 32) return EncryptPackageResult.Rejected("INVALID_PEK")
        if (gcmNonce.size != Profile01WireConstants.GCM_NONCE_BYTES) {
            return EncryptPackageResult.Rejected("INVALID_GCM_NONCE")
        }
        return runCatching {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(pek, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, gcmNonce),
            )
            cipher.updateAAD(aad)
            val output = cipher.doFinal(plaintext)
            val tagSize = Profile01WireConstants.GCM_TAG_BYTES
            if (output.size < tagSize) return EncryptPackageResult.Rejected("GCM_ENCRYPT_FAIL")
            val ciphertext = output.copyOfRange(0, output.size - tagSize)
            val tag = output.copyOfRange(output.size - tagSize, output.size)
            EncryptPackageResult.Ready(ciphertext = ciphertext, gcmTag = tag)
        }.getOrElse {
            EncryptPackageResult.Rejected("GCM_ENCRYPT_FAIL")
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExpandSha256(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val output = ByteArray(length)
        var offset = 0
        var previous = byteArrayOf()
        var counter = 1
        while (offset < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val copy = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, copy)
            offset += copy
            counter++
        }
        return output
    }

    private fun Long.u64Be(): ByteArray =
        byteArrayOf(
            (this shr 56).toByte(),
            (this shr 48).toByte(),
            (this shr 40).toByte(),
            (this shr 32).toByte(),
            (this shr 24).toByte(),
            (this shr 16).toByte(),
            (this shr 8).toByte(),
            this.toByte(),
        )

    data class PackagePlaintext(
        val conferenceMediaSecret: ByteArray,
        val membershipKeyContextDigest: ByteArray,
    )

    data class SrtpKeyMaterial(
        val masterKey: ByteArray,
        val masterSalt: ByteArray,
    )
}
