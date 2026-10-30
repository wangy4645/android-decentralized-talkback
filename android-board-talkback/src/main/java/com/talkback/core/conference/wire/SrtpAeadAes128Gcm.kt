package com.talkback.core.conference.wire

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 7714 AEAD_AES_128_GCM for SRTP (Profile 02 Q5).
 * IV = (0x0000 || SSRC || ROC || SEQ) XOR salt.
 */
object SrtpAeadAes128Gcm {
    private const val GCM_TAG_BITS = 128

    fun iv(salt12: ByteArray, ssrc: Int, roc: Int, seq: Int): ByteArray {
        require(salt12.size == 12)
        val counter = ByteBuffer.allocate(12)
            .putShort(0)
            .putInt(ssrc)
            .putInt(roc)
            .putShort(seq.toShort())
            .array()
        return ByteArray(12) { i -> (counter[i].toInt() xor salt12[i].toInt()).toByte() }
    }

    fun protect(
        headerAndHe: ByteArray,
        plaintextPayload: ByteArray,
        masterKey: ByteArray,
        masterSalt: ByteArray,
        ssrc: Int,
        roc: Int,
        seq: Int,
    ): ByteArray {
        require(masterKey.size == 16)
        require(masterSalt.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(masterKey, "AES")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, iv(masterSalt, ssrc, roc, seq)),
        )
        cipher.updateAAD(headerAndHe)
        val ctAndTag = cipher.doFinal(plaintextPayload)
        return headerAndHe + ctAndTag
    }

    fun unprotect(
        headerAndHe: ByteArray,
        ciphertextAndTag: ByteArray,
        masterKey: ByteArray,
        masterSalt: ByteArray,
        ssrc: Int,
        roc: Int,
        seq: Int,
    ): ByteArray? {
        if (ciphertextAndTag.size < ConferenceWireConstants.AEAD_TAG_OCTETS) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val key = SecretKeySpec(masterKey, "AES")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, iv(masterSalt, ssrc, roc, seq)),
            )
            cipher.updateAAD(headerAndHe)
            cipher.doFinal(ciphertextAndTag)
        } catch (_: Exception) {
            null
        }
    }
}
