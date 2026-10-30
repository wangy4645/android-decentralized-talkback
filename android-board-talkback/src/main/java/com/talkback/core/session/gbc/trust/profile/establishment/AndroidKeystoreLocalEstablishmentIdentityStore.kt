package com.talkback.core.session.gbc.trust.profile.establishment

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.X509EncodedKeySpec

/**
 * Android Keystore-backed local establishment identity (PR-EP-2).
 */
class AndroidKeystoreLocalEstablishmentIdentityStore(
    private val keyStoreName: String = ANDROID_KEYSTORE,
) : LocalEstablishmentKeystoreIdentityStore {
    override fun provisionFirstIdentity(
        moduleId: String,
        establishmentKeyVersion: Long,
    ): LocalEstablishmentKeystoreProvisionResult {
        if (moduleId.isBlank()) return LocalEstablishmentKeystoreProvisionResult.Failed("moduleId required")
        if (establishmentKeyVersion <= 0L) {
            return LocalEstablishmentKeystoreProvisionResult.Failed("establishmentKeyVersion must be positive")
        }
        val alias = LocalEstablishmentKeystoreAlias.forModule(moduleId, establishmentKeyVersion)
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(alias)) {
            return when (val existing = loadFromAlias(keyStore, alias, moduleId, establishmentKeyVersion)) {
                is LocalEstablishmentKeystoreLoadResult.Ready ->
                    LocalEstablishmentKeystoreProvisionResult.AlreadyProvisioned(existing.identity)
                is LocalEstablishmentKeystoreLoadResult.Unavailable ->
                    LocalEstablishmentKeystoreProvisionResult.Failed(existing.reason)
            }
        }
        return try {
            val generator =
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, keyStoreName)
            val spec =
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(RSA_KEY_SIZE_BITS)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setUserAuthenticationRequired(false)
                    .build()
            generator.initialize(spec)
            val keyPair = generator.generateKeyPair()
            if (!isNonExportablePrivateKey(keyPair.private)) {
                return LocalEstablishmentKeystoreProvisionResult.Failed("private key not non-exportable")
            }
            val spki = keyPair.public.encoded
            if (!EstablishmentSpkiValidator.isSupportedRsa3072(spki)) {
                return LocalEstablishmentKeystoreProvisionResult.Failed("generated key not RSA-3072")
            }
            LocalEstablishmentKeystoreProvisionResult.Created(
                Profile01LocalEstablishmentIdentitySnapshot(
                    moduleId = moduleId,
                    establishmentKeyVersion = establishmentKeyVersion,
                    publicKeySpki = spki.copyOf(),
                    keyAlias = alias,
                ),
            )
        } catch (ex: Exception) {
            LocalEstablishmentKeystoreProvisionResult.Failed(ex.message ?: "keystore provision failed")
        }
    }

    override fun loadIdentity(
        moduleId: String,
        establishmentKeyVersion: Long,
    ): LocalEstablishmentKeystoreLoadResult {
        if (moduleId.isBlank()) return LocalEstablishmentKeystoreLoadResult.Unavailable("moduleId required")
        if (establishmentKeyVersion <= 0L) {
            return LocalEstablishmentKeystoreLoadResult.Unavailable("establishmentKeyVersion must be positive")
        }
        val alias = LocalEstablishmentKeystoreAlias.forModule(moduleId, establishmentKeyVersion)
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(alias)) {
            return LocalEstablishmentKeystoreLoadResult.Unavailable("MISSING_KEYSTORE_IDENTITY")
        }
        return loadFromAlias(keyStore, alias, moduleId, establishmentKeyVersion)
    }

    private fun loadFromAlias(
        keyStore: KeyStore,
        alias: String,
        moduleId: String,
        establishmentKeyVersion: Long,
    ): LocalEstablishmentKeystoreLoadResult {
        return try {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                ?: return LocalEstablishmentKeystoreLoadResult.Unavailable("CORRUPT_KEYSTORE_ENTRY")
            val privateKey = entry.privateKey
            if (!isNonExportablePrivateKey(privateKey)) {
                return LocalEstablishmentKeystoreLoadResult.Unavailable("PRIVATE_KEY_EXPORTABLE")
            }
            val publicKey =
                KeyFactory.getInstance("RSA").generatePublic(
                    X509EncodedKeySpec(entry.certificate.publicKey.encoded),
                )
            val spki = publicKey.encoded
            if (!EstablishmentSpkiValidator.isSupportedRsa3072(spki)) {
                return LocalEstablishmentKeystoreLoadResult.Unavailable("UNSUPPORTED_KEY_ALGORITHM")
            }
            LocalEstablishmentKeystoreLoadResult.Ready(
                Profile01LocalEstablishmentIdentitySnapshot(
                    moduleId = moduleId,
                    establishmentKeyVersion = establishmentKeyVersion,
                    publicKeySpki = spki.copyOf(),
                    keyAlias = alias,
                ),
            )
        } catch (ex: Exception) {
            LocalEstablishmentKeystoreLoadResult.Unavailable(ex.message ?: "keystore load failed")
        }
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(keyStoreName).apply {
            load(null)
        }

    private fun isNonExportablePrivateKey(privateKey: PrivateKey): Boolean {
        if (privateKey is RSAPrivateKey && privateKey.encoded != null) {
            return false
        }
        return runCatching { privateKey.encoded }.getOrNull() == null
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val RSA_KEY_SIZE_BITS = 3072
    }
}
