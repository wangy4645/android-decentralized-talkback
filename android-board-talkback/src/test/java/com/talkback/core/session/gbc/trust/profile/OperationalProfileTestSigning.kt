package com.talkback.core.session.gbc.trust.profile

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.AlgorithmParameters
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec

/**
 * Test-only operational Profile authority signing (Layer B harness).
 * MUST NOT ship as production operational trust root tooling.
 *
 * Cryptographic domain is isolated from [com.talkback.core.session.gbc.crypto.GenerationFactTestSigning].
 */
object OperationalProfileTestSigning {
    private val keyPair: KeyPair by lazy {
        val kpg = KeyPairGenerator.getInstance("EC")
        val seed =
            SecureRandom.getInstance("SHA1PRNG").apply {
                setSeed("talkback-operational-profile-test-v1".toByteArray())
            }
        kpg.initialize(ECGenParameterSpec("secp256r1"), seed)
        kpg.generateKeyPair()
    }

    private val privateKey: PrivateKey by lazy { keyPair.private }

    val publicKeySpki: ByteArray by lazy { keyPair.public.encoded }

    fun signProtectedRevision(protectedBytes: ByteArray): ByteArray {
        val message = AuthenticatedProfileRevisionEnvelope.signatureInput(protectedBytes)
        val signatureRs = signLowS(message)
        return AuthenticatedProfileRevisionEnvelope.build(protectedBytes, signatureRs)
    }

    fun signPayloadV2(payload: GenerationFactProfileTrustPayload): ByteArray {
        val protectedBytes = ProfileRevisionCanonicalCodec.encodeV2(payload)
        return signProtectedRevision(protectedBytes)
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
}
