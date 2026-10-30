package com.talkback.core.session.gbc.crypto

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * ECDSA P-256 / SHA-256 verification with low-S enforcement (C1).
 */
object EcdsaP256Verifier {
    private val curveParams: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    private val curveOrder: BigInteger by lazy { curveParams.order }

    private val halfOrder: BigInteger by lazy { curveOrder.shiftRight(1) }

    fun verify(
        message: ByteArray,
        signatureRs: ByteArray,
        publicKey: PublicKey,
    ): Boolean {
        if (!validateSignatureForm(signatureRs)) return false
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initVerify(publicKey)
        signature.update(message)
        return runCatching { signature.verify(derEncode(signatureRs)) }.getOrDefault(false)
    }

    fun validateSignatureForm(signatureRs: ByteArray): Boolean {
        if (signatureRs.size != 64) return false
        val r = BigInteger(1, signatureRs.copyOfRange(0, 32))
        val s = BigInteger(1, signatureRs.copyOfRange(32, 64))
        if (r <= BigInteger.ZERO || r >= curveOrder) return false
        if (s <= BigInteger.ZERO || s > halfOrder) return false
        return true
    }

    fun publicKeyFromX963(x963: ByteArray): PublicKey? =
        runCatching {
            require(x963.size == 65 && x963[0] == 0x04.toByte()) { "expected uncompressed X9.63" }
            val x = BigInteger(1, x963.copyOfRange(1, 33))
            val y = BigInteger(1, x963.copyOfRange(33, 65))
            KeyFactory.getInstance("EC")
                .generatePublic(ECPublicKeySpec(ECPoint(x, y), curveParams))
        }.getOrNull()

    fun publicKeyFromSpki(spki: ByteArray): PublicKey? =
        runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        }.getOrNull()

    private fun derEncode(signatureRs: ByteArray): ByteArray {
        val r = BigInteger(1, signatureRs.copyOfRange(0, 32))
        val s = BigInteger(1, signatureRs.copyOfRange(32, 64))
        val rBytes = toUnsignedBytes(r)
        val sBytes = toUnsignedBytes(s)
        val sequenceBody = byteArrayOf(0x02) + lengthBytes(rBytes.size) + rBytes +
            byteArrayOf(0x02) + lengthBytes(sBytes.size) + sBytes
        return byteArrayOf(0x30) + lengthBytes(sequenceBody.size) + sequenceBody
    }

    private fun toUnsignedBytes(value: BigInteger): ByteArray {
        // DER INTEGER requires two's-complement encoding: if the high bit of
        // the unsigned magnitude is set, a leading 0x00 must be present.
        // Stripping that byte breaks Conscrypt verification (Android) for a
        // subset of otherwise-valid ECDSA signatures.
        return value.toByteArray()
    }

    private fun lengthBytes(length: Int): ByteArray =
        when {
            length < 128 -> byteArrayOf(length.toByte())
            length < 256 -> byteArrayOf(0x81.toByte(), length.toByte())
            else -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
        }
}
