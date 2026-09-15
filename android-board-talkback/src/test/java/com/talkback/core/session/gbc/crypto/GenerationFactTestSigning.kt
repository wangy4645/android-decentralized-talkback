package com.talkback.core.session.gbc.crypto

import com.talkback.core.model.PredecessorWire
import com.talkback.core.session.gbc.GenerationFactCandidate
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.ECGenParameterSpec
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.AlgorithmParameters
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Test-only signing support for PV-1 fixtures. MUST NOT ship as production authority.
 */
object GenerationFactTestSigning {
    private val privateKey: PrivateKey by lazy {
        KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(FIXED_PKCS8_PRIVATE_KEY))
    }

    val publicKeySpki: ByteArray by lazy {
        KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(FIXED_SPKI_PUBLIC_KEY))
            .encoded
    }

    fun signAuthority(
        authority: GenerationFactCanonicalCodec.AuthoritySemantics,
        signerKeyVersion: Long,
        trustBindingRevision: Long,
    ): SignedGenerationFactEnvelope {
        val authorityBytes = GenerationFactCanonicalCodec.encodeAuthority(authority)
        val contextBytes =
            GenerationFactCanonicalCodec.encodeVerificationContext(
                GenerationFactCanonicalCodec.VerificationContext(
                    originAuthorityIdentity = authority.originAuthorityIdentity,
                    signerKeyVersion = signerKeyVersion,
                    trustBindingRevision = trustBindingRevision,
                ),
            )
        val message = GenerationFactCanonicalCodec.signatureInput(authorityBytes, contextBytes)
        val signatureRs = signLowS(message)
        return SignedGenerationFactEnvelope(authorityBytes, contextBytes, signatureRs)
    }

    fun signMessage(message: ByteArray): ByteArray = signLowS(message)

    fun buildCandidate(
        authority: GenerationFactCanonicalCodec.AuthoritySemantics,
        signerKeyVersion: Long,
        trustBindingRevision: Long,
        inclusionProof: ByteArray? = null,
    ): GenerationFactCandidate {
        val envelope = signAuthority(authority, signerKeyVersion, trustBindingRevision)
        val digestHex = GenerationFactCanonicalCodec.semanticDigestHex(envelope.authorityCanonicalBytes)
        val bundle =
            GenerationFactVerificationBundle(
                signedFactBytes = envelope.toSignedFactBytes(),
                historicalInclusionProof = inclusionProof,
            )
        val predecessor =
            when (val p = authority.predecessor) {
                PredecessorWire.Absent,
                PredecessorWire.None,
                -> null
                is PredecessorWire.Id -> p.generationIdentity
            }
        return GenerationFactCandidate(
            claimedFactIdentity = digestHex,
            claimedGenerationIdentity = authority.generationIdentity,
            claimedPredecessorGenerationIdentity = predecessor,
            claimedOriginAuthorityIdentity = authority.originAuthorityIdentity,
            claimedAttestsCurrent = authority.attestsCurrent,
            claimedSemanticDigest = digestHex,
            opaqueMaterial = bundle.encodeToVerificationMaterial(),
        )
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

    // PV-1 externally fixed golden-vector key material (test only).
    private val FIXED_PKCS8_PRIVATE_KEY: ByteArray =
        hex(
            "3041020100301306072a8648ce3d020106082a8648ce3d030107042730250201010420" +
                "241ed51cb84c1723036d52794fc9c7298de352deafa31bd3f438a9066ecee34a",
        )

    private val FIXED_SPKI_PUBLIC_KEY: ByteArray =
        hex(
            "3059301306072a8648ce3d020106082a8648ce3d03010703420004" +
                "1deb748cf06e265b8a8d05b5c68c35e83336dca2e48208b470f1e98eaab682e" +
                "798445ccd797f583043b59ae971ae59f3cc97c1738d01df29cf37069d296a968c",
        )

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
