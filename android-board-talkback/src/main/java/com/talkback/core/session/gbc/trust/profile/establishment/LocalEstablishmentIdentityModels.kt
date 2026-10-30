package com.talkback.core.session.gbc.trust.profile.establishment

import java.security.MessageDigest

/**
 * Runtime-readable local establishment public metadata (PR-EP-2).
 *
 * Private key material remains in Android Keystore — referenced only by [keyAlias].
 */
data class Profile01LocalEstablishmentIdentitySnapshot(
    val moduleId: String,
    val establishmentKeyVersion: Long,
    val publicKeySpki: ByteArray,
    val keyAlias: String,
) {
    fun publicKeyFingerprintSha256Hex(): String =
        publicKeySpki.sha256Hex()
}

fun LocalEstablishmentKeystoreAlias.forModule(
    moduleId: String,
    establishmentKeyVersion: Long,
): String = "talkback-establishment-$moduleId-v$establishmentKeyVersion"

object LocalEstablishmentKeystoreAlias

internal fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }

sealed class LocalEstablishmentKeystoreProvisionResult {
    data class Created(
        val identity: Profile01LocalEstablishmentIdentitySnapshot,
    ) : LocalEstablishmentKeystoreProvisionResult()

    data class AlreadyProvisioned(
        val identity: Profile01LocalEstablishmentIdentitySnapshot,
    ) : LocalEstablishmentKeystoreProvisionResult()

    data class Failed(
        val reason: String,
    ) : LocalEstablishmentKeystoreProvisionResult()
}

sealed class LocalEstablishmentKeystoreLoadResult {
    data class Ready(
        val identity: Profile01LocalEstablishmentIdentitySnapshot,
    ) : LocalEstablishmentKeystoreLoadResult()

    data class Unavailable(
        val reason: String,
    ) : LocalEstablishmentKeystoreLoadResult()
}

/**
 * Local RSA-3072 establishment identity in Android Keystore.
 *
 * Profile drives authorized version/SPKI expectation; this store only proves possession.
 * Never auto-repairs version mismatch by generating a new key.
 */
interface LocalEstablishmentKeystoreIdentityStore {
    /**
     * First provisioning ceremony — creates a non-exportable RSA-3072 key at the version alias.
     * If the alias already exists, returns [LocalEstablishmentKeystoreProvisionResult.AlreadyProvisioned]
     * without regenerating.
     */
    fun provisionFirstIdentity(
        moduleId: String,
        establishmentKeyVersion: Long,
    ): LocalEstablishmentKeystoreProvisionResult

    /**
     * Load an existing identity — never generates a new key.
     */
    fun loadIdentity(
        moduleId: String,
        establishmentKeyVersion: Long,
    ): LocalEstablishmentKeystoreLoadResult
}
