package com.talkback.core.session.gbc.trust.profile.establishment

/**
 * Compares an authenticated profile local establishment row against local Keystore identity.
 *
 * Does not provision keys, mutate trust stores, or perform PAP verification.
 */
data class AuthenticatedLocalEstablishmentBinding(
    val moduleId: String,
    val establishmentAlgorithm: EstablishmentAlgorithm,
    val establishmentKeyVersion: Long,
    val establishmentPublicKeySpki: ByteArray,
)

sealed class LocalEstablishmentBindingVerifyResult {
    data object Verified : LocalEstablishmentBindingVerifyResult()

    data class ModuleMismatch(
        val expectedModuleId: String,
        val actualModuleId: String,
    ) : LocalEstablishmentBindingVerifyResult()

    data class AlgorithmMismatch(
        val expected: EstablishmentAlgorithm,
        val actual: EstablishmentAlgorithm,
    ) : LocalEstablishmentBindingVerifyResult()

    data class VersionMismatch(
        val expectedVersion: Long,
        val actualVersion: Long,
    ) : LocalEstablishmentBindingVerifyResult()

    data class LocalEstablishmentKeyMismatch(
        val expectedFingerprintSha256Hex: String,
        val actualFingerprintSha256Hex: String,
    ) : LocalEstablishmentBindingVerifyResult()

    data class IdentityUnavailable(
        val reason: String,
    ) : LocalEstablishmentBindingVerifyResult()
}

object LocalEstablishmentBindingVerifier {
    fun verify(
        profileBinding: AuthenticatedLocalEstablishmentBinding,
        keystoreIdentity: Profile01LocalEstablishmentIdentitySnapshot?,
        keystoreLoad: LocalEstablishmentKeystoreLoadResult? = null,
    ): LocalEstablishmentBindingVerifyResult {
        if (keystoreIdentity == null) {
            val reason =
                when (keystoreLoad) {
                    is LocalEstablishmentKeystoreLoadResult.Unavailable -> keystoreLoad.reason
                    else -> "MISSING_KEYSTORE_IDENTITY"
                }
            return LocalEstablishmentBindingVerifyResult.IdentityUnavailable(reason)
        }
        if (profileBinding.moduleId != keystoreIdentity.moduleId) {
            return LocalEstablishmentBindingVerifyResult.ModuleMismatch(
                expectedModuleId = profileBinding.moduleId,
                actualModuleId = keystoreIdentity.moduleId,
            )
        }
        if (profileBinding.establishmentAlgorithm != EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1) {
            return LocalEstablishmentBindingVerifyResult.AlgorithmMismatch(
                expected = profileBinding.establishmentAlgorithm,
                actual = EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1,
            )
        }
        if (profileBinding.establishmentKeyVersion != keystoreIdentity.establishmentKeyVersion) {
            return LocalEstablishmentBindingVerifyResult.VersionMismatch(
                expectedVersion = profileBinding.establishmentKeyVersion,
                actualVersion = keystoreIdentity.establishmentKeyVersion,
            )
        }
        if (!profileBinding.establishmentPublicKeySpki.contentEquals(keystoreIdentity.publicKeySpki)) {
            return LocalEstablishmentBindingVerifyResult.LocalEstablishmentKeyMismatch(
                expectedFingerprintSha256Hex =
                    profileBinding.establishmentPublicKeySpki.sha256Hex(),
                actualFingerprintSha256Hex = keystoreIdentity.publicKeySpki.sha256Hex(),
            )
        }
        return LocalEstablishmentBindingVerifyResult.Verified
    }

    fun localBindingFromRevision(
        revision: AuthenticatedEstablishmentProfileRevision,
    ): AuthenticatedLocalEstablishmentBinding? {
        val binding =
            revision.payload.establishmentBindings.firstOrNull {
                it.moduleId == revision.payload.localModuleId
            } ?: return null
        return AuthenticatedLocalEstablishmentBinding(
            moduleId = binding.moduleId,
            establishmentAlgorithm = binding.establishmentAlgorithm,
            establishmentKeyVersion = binding.establishmentKeyVersion,
            establishmentPublicKeySpki = binding.establishmentPublicKeySpki.copyOf(),
        )
    }

    fun verifyRevisionAgainstKeystore(
        revision: AuthenticatedEstablishmentProfileRevision,
        keystoreStore: LocalEstablishmentKeystoreIdentityStore,
    ): LocalEstablishmentBindingVerifyResult {
        val profileBinding = localBindingFromRevision(revision) ?: return IdentityUnavailable("missing local establishment binding")
        val loaded =
            keystoreStore.loadIdentity(
                profileBinding.moduleId,
                profileBinding.establishmentKeyVersion,
            )
        val identity =
            when (loaded) {
                is LocalEstablishmentKeystoreLoadResult.Ready -> loaded.identity
                is LocalEstablishmentKeystoreLoadResult.Unavailable ->
                    return LocalEstablishmentBindingVerifyResult.IdentityUnavailable(loaded.reason)
            }
        return verify(profileBinding, identity, loaded)
    }

    private fun IdentityUnavailable(reason: String): LocalEstablishmentBindingVerifyResult =
        LocalEstablishmentBindingVerifyResult.IdentityUnavailable(reason)
}
