package com.talkback.appprod.fieldtest

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Field-test Generation Fact signer material (androidTest only).
 * Bit-identical to [com.talkback.core.session.gbc.crypto.GenerationFactTestSigning].
 */
object FieldTestGenerationFactAuthority {
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

    val pkcs8PrivateKey: ByteArray = FIXED_PKCS8_PRIVATE_KEY

    val publicKeySpki: ByteArray = FIXED_SPKI_PUBLIC_KEY

    val privateKey: PrivateKey by lazy {
        KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(FIXED_PKCS8_PRIVATE_KEY))
    }

    init {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(FIXED_SPKI_PUBLIC_KEY))
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
