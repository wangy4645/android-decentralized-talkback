package com.talkback.core.session.gbc.trust.profile.establishment

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Test-only establishment identity store — mirrors [AndroidKeystoreLocalEstablishmentIdentityStore]
 * contract without AndroidKeyStore (Robolectric/JVM safe).
 *
 * MUST NOT be used in production wiring.
 */
class TestLocalEstablishmentKeystoreIdentityStore : LocalEstablishmentKeystoreIdentityStore {
    private val identitiesByAlias = ConcurrentHashMap<String, StoredIdentity>()

    override fun provisionFirstIdentity(
        moduleId: String,
        establishmentKeyVersion: Long,
    ): LocalEstablishmentKeystoreProvisionResult {
        if (moduleId.isBlank()) return LocalEstablishmentKeystoreProvisionResult.Failed("moduleId required")
        if (establishmentKeyVersion <= 0L) {
            return LocalEstablishmentKeystoreProvisionResult.Failed("establishmentKeyVersion must be positive")
        }
        val alias = LocalEstablishmentKeystoreAlias.forModule(moduleId, establishmentKeyVersion)
        val existing = identitiesByAlias[alias]
        if (existing != null) {
            return LocalEstablishmentKeystoreProvisionResult.AlreadyProvisioned(existing.snapshot())
        }
        return try {
            val keyPair =
                KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_SIZE_BITS) }.generateKeyPair()
            val spki = keyPair.public.encoded
            if (!EstablishmentSpkiValidator.isSupportedRsa3072(spki)) {
                return LocalEstablishmentKeystoreProvisionResult.Failed("generated key not RSA-3072")
            }
            val stored =
                StoredIdentity(
                    moduleId = moduleId,
                    establishmentKeyVersion = establishmentKeyVersion,
                    publicKeySpki = spki.copyOf(),
                    keyAlias = alias,
                    privateKey = NonExportablePrivateKey(keyPair.private),
                )
            identitiesByAlias[alias] = stored
            LocalEstablishmentKeystoreProvisionResult.Created(stored.snapshot())
        } catch (ex: Exception) {
            LocalEstablishmentKeystoreProvisionResult.Failed(ex.message ?: "test provision failed")
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
        val stored =
            identitiesByAlias[alias]
                ?: return LocalEstablishmentKeystoreLoadResult.Unavailable("MISSING_KEYSTORE_IDENTITY")
        return LocalEstablishmentKeystoreLoadResult.Ready(stored.snapshot())
    }

    fun clear() {
        identitiesByAlias.clear()
    }

    private data class StoredIdentity(
        val moduleId: String,
        val establishmentKeyVersion: Long,
        val publicKeySpki: ByteArray,
        val keyAlias: String,
        val privateKey: NonExportablePrivateKey,
    ) {
        fun snapshot(): Profile01LocalEstablishmentIdentitySnapshot =
            Profile01LocalEstablishmentIdentitySnapshot(
                moduleId = moduleId,
                establishmentKeyVersion = establishmentKeyVersion,
                publicKeySpki = publicKeySpki.copyOf(),
                keyAlias = keyAlias,
            )
    }

    private class NonExportablePrivateKey(
        private val delegate: PrivateKey,
    ) : PrivateKey {
        override fun getAlgorithm(): String = delegate.algorithm

        override fun getFormat(): String? = null

        override fun getEncoded(): ByteArray? = null
    }

    companion object {
        private const val RSA_KEY_SIZE_BITS = 3072
    }
}

/**
 * Returns Android Keystore store when the provider is available; otherwise null.
 */
fun androidKeystoreLocalEstablishmentIdentityStoreOrNull(): LocalEstablishmentKeystoreIdentityStore? =
    runCatching {
        java.security.KeyStore.getInstance("AndroidKeyStore").load(null)
        AndroidKeystoreLocalEstablishmentIdentityStore()
    }.getOrNull()
