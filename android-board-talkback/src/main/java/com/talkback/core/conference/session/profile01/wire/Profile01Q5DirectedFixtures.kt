package com.talkback.core.conference.session.profile01.wire

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Q5 public test RSA establishment key — MUST NOT be used in product Keystore.
 *
 * Directed PRODUCT-CHAIN soak / unit vectors only.
 */
class Profile01Q5TestRecipientKeyEstablishmentSeam(
    private val keysByModuleAndVersion: Map<Pair<String, Long>, PrivateKey>,
) : Profile01RecipientKeyEstablishmentSeam {
    override fun unwrapPek(
        wrappedPek: ByteArray,
        recipientModuleId: String,
        recipientKeyVersion: Long,
    ): Profile01PekUnwrapResult {
        val privateKey =
            keysByModuleAndVersion[recipientModuleId to recipientKeyVersion]
                ?: return Profile01PekUnwrapResult.Rejected("UNKNOWN_ESTABLISHMENT_KEY")
        return runCatching {
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            val spec =
                OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA1,
                    PSource.PSpecified.DEFAULT,
                )
            cipher.init(Cipher.DECRYPT_MODE, privateKey, spec)
            Profile01PekUnwrapResult.Ready(cipher.doFinal(wrappedPek))
        }.getOrElse {
            Profile01PekUnwrapResult.Rejected("OAEP_DECRYPT_FAIL")
        }
    }

    companion object {
        private val Q5_RSA_PRIVATE_PEM =
            """
            -----BEGIN PRIVATE KEY-----
            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
            Mz37LTuHBR1cnDKL/ND0zFA=
            -----END PRIVATE KEY-----
            """.trimIndent()

        fun testEstablishmentPublicKeySpki(): ByteArray {
            val privateKey = loadPrivateKey(Q5_RSA_PRIVATE_PEM) as RSAPrivateCrtKey
            val public =
                KeyFactory.getInstance("RSA").generatePublic(
                    RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent),
                )
            return public.encoded
        }

        fun defaultSeam(): Profile01Q5TestRecipientKeyEstablishmentSeam {
            val privateKey = loadPrivateKey(Q5_RSA_PRIVATE_PEM)
            return Profile01Q5TestRecipientKeyEstablishmentSeam(mapOf("M02" to 4L to privateKey))
        }

        /** Unit tests only — MUST NOT be used in production Keystore path. */
        internal fun q5TestOnlyEstablishmentPrivateKey(): PrivateKey = loadPrivateKey(Q5_RSA_PRIVATE_PEM)

        private fun loadPrivateKey(pem: String): PrivateKey {
            val der =
                pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("\\s".toRegex(), "")
            val bytes = Base64.getDecoder().decode(der)
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        }
    }
}

/**
 * Embedded Q5 crypto vectors for on-device directed soak (no docs/ filesystem dependency).
 * PUBLIC TEST DATA ONLY.
 */
object Profile01Q5EmbeddedVectors {
    const val RECIPIENT_MODULE_ID = "M02"
    const val RECIPIENT_KEY_VERSION = 4L

    fun wirePackage(): Profile01WireMediaKeyPackage =
        Profile01WireMediaKeyPackage(
            signedFactBytes = byteArrayOf(),
            factDigest = byteArrayOf(),
            fullCanonicalBytes = byteArrayOf(),
            conferenceId = hex("00112233445566778899aabbccddeeff"),
            conferenceEpoch = 7L,
            ownerModuleId = "M01",
            membershipVersion = 2L,
            mediaKeyEpoch = 3L,
            membershipFactDigest =
                hex("ed80b78fc831e5f980946af3b1b3432fb2b50995d18b8bbfeccdeaaaaeed4d5c"),
            mediaKeyCommitment =
                hex("de64f72b1f4c5499302b4575816dbadd78f2910916a16f512326949b4f121cd2"),
            recipientModuleId = RECIPIENT_MODULE_ID,
            packageIdentity = hex("aabbccddeeff00112233445566778899"),
            wrappedPek =
                hex(
                    "3c544bcb6fe796d90e7344bd7fe6c6690f90b078e4894b0c2c7978f4a279a0377a781f33f503e264c0bd44f92ae5fcc945806f59d660166f2baa87c0f22f350562f558819d82a981f1b26a5a3bf29ea144f1426870bb416f3c5b1e094b37cd2a9d372f48fad7c0cb7a05429921c657b81ae701eb19b62fd240185b1a1e2c3c03239ffd515c2f22c06d3fe407e2ebba6fcfca8f48652521b712be346b99e10a4e58b13d3a856e4cf5aa8513cc8c573ff3fc11669991a66e07d7a929b76dbbb0607514b3630e29da1b2f326892584bfa3afb2df16602d2467848dc91e9409704aae51c458cf89b4c3a8ffab393df3101baabbb95b4c9a5252b74a0e0839e5982cad55e50b025e8f014e508be4dc93d23884b0fe822ad63ad6a52f203677889b507f1f5e5b0fc24230ac6a968a1a6188f98a63e624fea011aaaa5d01aa9944961b57f6ca074c148fdd87a41e0dd6ca8f758e10e34529fc96a34fe20f3ef73395d3149a81046feda520baf56d99adbc22e18aa3ecbb329df8688c76523c2e80d8df2",
                ),
            gcmNonce = hex("0102030405060708090a0b0c"),
            ciphertext =
                hex(
                    "34d5aeaf3ab4444b4a81c4be7393b74fe20cd9a74ea0ef50f970a640f21ed44f67c9cd3c04244a9a24c8f8b16f8ac14c8851a781baf7eadd2fe1501f2a22ed4202305a7e09f4a0fe0b",
                ),
            gcmTag = hex("0395a2e27cfabca58049c5a5cb12364c"),
            recipientKeyVersion = RECIPIENT_KEY_VERSION,
        )

    val expectedSrtpMasterKeyHex = "84dcac4ffce0573efc89ffa1854481fa"
    val expectedSrtpMasterSaltHex = "c4bb984c01c252e59342b57b"

    fun hex(value: String): ByteArray {
        val clean = value.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val idx = i * 2
            out[i] = clean.substring(idx, idx + 2).toInt(16).toByte()
        }
        return out
    }
}
