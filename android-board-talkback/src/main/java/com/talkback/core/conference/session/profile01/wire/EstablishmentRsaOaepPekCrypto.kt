package com.talkback.core.conference.session.profile01.wire

import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * RSA-OAEP PEK unwrap (SHA-256 + MGF1-SHA1) aligned with [Profile01PackageCrypto.wrapPekWithEstablishmentPublicKey].
 */
internal object EstablishmentRsaOaepPekCrypto {
    private const val RSA_OAEP_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

    private val oaepSpec: OAEPParameterSpec =
        OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA1,
            PSource.PSpecified.DEFAULT,
        )

    fun unwrap(
        wrappedPek: ByteArray,
        privateKey: PrivateKey,
    ): Profile01PekUnwrapResult =
        runCatching {
            val cipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, privateKey, oaepSpec)
            Profile01PekUnwrapResult.Ready(cipher.doFinal(wrappedPek))
        }.getOrElse {
            Profile01PekUnwrapResult.Rejected("OAEP_DECRYPT_FAIL")
        }
}
