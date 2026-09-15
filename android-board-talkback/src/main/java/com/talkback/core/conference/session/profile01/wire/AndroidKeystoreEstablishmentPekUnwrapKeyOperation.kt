package com.talkback.core.conference.session.profile01.wire

import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.PrivateKey

/**
 * Injectable PEK unwrap keyed only by profile-authorized Keystore alias.
 *
 * Wire [recipientKeyVersion] is never used to construct or resolve aliases here.
 */
fun interface EstablishmentPekUnwrapKeyOperation {
    fun unwrap(
        wrappedPek: ByteArray,
        authorizedKeyAlias: String,
    ): Profile01PekUnwrapResult
}

/**
 * Android Keystore RSA-OAEP unwrap using an alias from verified local establishment identity.
 */
class AndroidKeystoreEstablishmentPekUnwrapKeyOperation(
    private val keyStoreName: String = ANDROID_KEYSTORE,
) : EstablishmentPekUnwrapKeyOperation {
    override fun unwrap(
        wrappedPek: ByteArray,
        authorizedKeyAlias: String,
    ): Profile01PekUnwrapResult {
        if (authorizedKeyAlias.isBlank()) {
            return Profile01PekUnwrapResult.Rejected("KEY_UNAVAILABLE")
        }
        val keyStore =
            runCatching {
                KeyStore.getInstance(keyStoreName).apply { load(null) }
            }.getOrElse {
                return Profile01PekUnwrapResult.Rejected("KEY_UNAVAILABLE")
            }
        if (!keyStore.containsAlias(authorizedKeyAlias)) {
            return Profile01PekUnwrapResult.Rejected("KEY_UNAVAILABLE")
        }
        val entry =
            runCatching {
                keyStore.getEntry(authorizedKeyAlias, null) as? KeyStore.PrivateKeyEntry
            }.getOrNull()
                ?: return Profile01PekUnwrapResult.Rejected("KEY_UNAVAILABLE")
        if (entry.privateKey.algorithm != KeyProperties.KEY_ALGORITHM_RSA) {
            return Profile01PekUnwrapResult.Rejected("KEY_UNAVAILABLE")
        }
        if (runCatching { entry.privateKey.encoded }.getOrNull() != null) {
            return Profile01PekUnwrapResult.Rejected("KEY_UNAVAILABLE")
        }
        return EstablishmentRsaOaepPekCrypto.unwrap(wrappedPek, entry.privateKey)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
