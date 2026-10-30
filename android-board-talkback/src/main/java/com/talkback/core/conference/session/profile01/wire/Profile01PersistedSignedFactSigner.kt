package com.talkback.core.conference.session.profile01.wire

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

interface Profile01SignedFactSigner {
    val signerModuleId: String
    val signerKeyVersion: Long

    fun signFullFact(fullCanonicalBytes: ByteArray): ByteArray
}

/**
 * Production-loadable Profile01 signed-fact signer (ECDSA P-256 low-S).
 *
 * Reuses persisted PKCS8 from field provisioning; signs with Profile01 SIGNATURE_DOMAIN.
 */
class Profile01PersistedSignedFactSigner private constructor(
    private val privateKey: PrivateKey,
    override val signerModuleId: String,
    override val signerKeyVersion: Long,
) : Profile01SignedFactSigner {
    override fun signFullFact(fullCanonicalBytes: ByteArray): ByteArray {
        val message = Profile01WireConstants.SIGNATURE_DOMAIN + fullCanonicalBytes
        return signLowS(message)
    }

    private fun signLowS(message: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(message)
        val der = signature.sign()
        val (r, s) = parseDerSignature(der)
        val normalizedS = normalizeLowS(s)
        return fixedWidth(r) + fixedWidth(normalizedS)
    }

    private data class DerLength(val length: Int, val bytesConsumed: Int)

    private fun readDerLength(data: ByteArray, offset: Int): DerLength {
        val first = data[offset].toInt() and 0xFF
        return if (first and 0x80 == 0) {
            DerLength(first, 1)
        } else {
            val numBytes = first and 0x7F
            var length = 0
            for (i in 1..numBytes) {
                length = (length shl 8) or (data[offset + i].toInt() and 0xFF)
            }
            DerLength(length, 1 + numBytes)
        }
    }

    private fun parseDerSignature(der: ByteArray): Pair<BigInteger, BigInteger> {
        var offset = 0
        require(der[offset++].toInt() == 0x30)
        val seqLen = readDerLength(der, offset)
        offset += seqLen.bytesConsumed
        require(der[offset++].toInt() == 0x02)
        val rLen = readDerLength(der, offset)
        offset += rLen.bytesConsumed
        val r = BigInteger(1, der.copyOfRange(offset, offset + rLen.length))
        offset += rLen.length
        require(der[offset++].toInt() == 0x02)
        val sLen = readDerLength(der, offset)
        offset += sLen.bytesConsumed
        val s = BigInteger(1, der.copyOfRange(offset, offset + sLen.length))
        return r to s
    }

    private fun normalizeLowS(s: BigInteger): BigInteger {
        val order =
            AlgorithmParameters.getInstance("EC")
                .apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
                .order
        val half = order.shiftRight(1)
        return if (s > half) order - s else s
    }

    private fun fixedWidth(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        val trimmed =
            if (bytes.size > 32 && bytes[0] == 0.toByte()) {
                bytes.copyOfRange(1, bytes.size)
            } else {
                bytes
            }
        return ByteArray(32 - trimmed.size) + trimmed
    }

    companion object {
        fun fromPkcs8(
            pkcs8PrivateKey: ByteArray,
            signerModuleId: String,
            signerKeyVersion: Long,
        ): Profile01PersistedSignedFactSigner? {
            if (pkcs8PrivateKey.isEmpty() || signerModuleId.isBlank() || signerKeyVersion <= 0L) {
                return null
            }
            return runCatching {
                val keyFactory = KeyFactory.getInstance("EC")
                val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(pkcs8PrivateKey))
                Profile01PersistedSignedFactSigner(
                    privateKey = privateKey,
                    signerModuleId = signerModuleId,
                    signerKeyVersion = signerKeyVersion,
                )
            }.getOrNull()
        }
    }
}
