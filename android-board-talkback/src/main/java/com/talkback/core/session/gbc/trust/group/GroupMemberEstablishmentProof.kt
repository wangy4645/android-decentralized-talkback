package com.talkback.core.session.gbc.trust.group

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated advertisement: moduleId owns establishment SPKI (Phase 0 proof).
 */
data class GroupMemberEstablishmentAdvertisement(
    val moduleId: String,
    val establishmentKeyVersion: Long,
    val establishmentPublicKeySpki: ByteArray,
    val nonceHex: String,
    val proofHex: String,
) {
    fun canonicalPayload(): String {
        val spkiHash = establishmentPublicKeySpki.sha256Hex()
        return listOf(
            moduleId.trim().uppercase(Locale.US),
            establishmentKeyVersion.toString(),
            spkiHash,
            nonceHex.lowercase(Locale.US),
        ).joinToString("|")
    }
}

object GroupMemberEstablishmentProof {
    fun sign(
        macKey: ByteArray,
        moduleId: String,
        establishmentKeyVersion: Long,
        establishmentPublicKeySpki: ByteArray,
        nonceHex: String,
    ): String {
        val ad =
            GroupMemberEstablishmentAdvertisement(
                moduleId = moduleId,
                establishmentKeyVersion = establishmentKeyVersion,
                establishmentPublicKeySpki = establishmentPublicKeySpki,
                nonceHex = nonceHex,
                proofHex = "",
            )
        return hmacHex(macKey, ad.canonicalPayload())
    }

    fun verify(
        macKey: ByteArray,
        advertisement: GroupMemberEstablishmentAdvertisement,
    ): Boolean {
        if (advertisement.proofHex.isBlank()) return false
        val expected =
            sign(
                macKey = macKey,
                moduleId = advertisement.moduleId,
                establishmentKeyVersion = advertisement.establishmentKeyVersion,
                establishmentPublicKeySpki = advertisement.establishmentPublicKeySpki,
                nonceHex = advertisement.nonceHex,
            )
        return constantTimeEquals(expected, advertisement.proofHex)
    }

    private fun hmacHex(
        macKey: ByteArray,
        message: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        val bytes = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean {
        val aBytes = a.lowercase(Locale.US).toByteArray(StandardCharsets.UTF_8)
        val bBytes = b.lowercase(Locale.US).toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(aBytes, bBytes)
    }
}

private fun ByteArray.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return digest.joinToString("") { "%02x".format(Locale.US, it) }
}
