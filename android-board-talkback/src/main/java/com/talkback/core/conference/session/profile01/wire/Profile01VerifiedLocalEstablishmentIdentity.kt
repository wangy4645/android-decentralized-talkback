package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.AuthenticatedLocalEstablishmentBinding
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentAlgorithm
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentBindingVerifier
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentBindingVerifyResult
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreIdentityStore
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreLoadResult

/**
 * Resolves verified local establishment identity from accepted profile + Keystore possession.
 *
 * Does not provision, rotate, or accept profile revisions.
 */
object Profile01VerifiedLocalEstablishmentIdentity {
    fun resolve(
        establishmentStore: AcceptedLocalEstablishmentTrustStateStore,
        keystoreStore: LocalEstablishmentKeystoreIdentityStore,
        localModuleId: String,
    ): Profile01LocalEstablishmentIdentityAvailability {
        if (localModuleId.isBlank()) {
            return Profile01LocalEstablishmentIdentityAvailability.Unavailable("localModuleId required")
        }
        val snapshot = establishmentStore.currentSnapshot()
            ?: return Profile01LocalEstablishmentIdentityAvailability.Unavailable("MISSING_ESTABLISHMENT_PROFILE")
        val localBinding =
            snapshot.bindingsByKey.values
                .asSequence()
                .filter { binding ->
                    binding.moduleId == localModuleId &&
                        binding.keyState == GenerationFactKeyState.ACTIVE
                }
                .maxByOrNull { it.establishmentKeyVersion }
            ?: return Profile01LocalEstablishmentIdentityAvailability.Unavailable(
                "MISSING_LOCAL_ESTABLISHMENT_BINDING",
            )
        val profileBinding =
            AuthenticatedLocalEstablishmentBinding(
                moduleId = localBinding.moduleId,
                establishmentAlgorithm = EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1,
                establishmentKeyVersion = localBinding.establishmentKeyVersion,
                establishmentPublicKeySpki = localBinding.establishmentPublicKeySpki.copyOf(),
            )
        val loaded =
            keystoreStore.loadIdentity(
                profileBinding.moduleId,
                profileBinding.establishmentKeyVersion,
            )
        val identity =
            when (loaded) {
                is LocalEstablishmentKeystoreLoadResult.Ready -> loaded.identity
                is LocalEstablishmentKeystoreLoadResult.Unavailable ->
                    return Profile01LocalEstablishmentIdentityAvailability.Unavailable(loaded.reason)
            }
        return when (
            val verify =
                LocalEstablishmentBindingVerifier.verify(profileBinding, identity, loaded)
        ) {
            LocalEstablishmentBindingVerifyResult.Verified ->
                Profile01LocalEstablishmentIdentityAvailability.Verified(identity)
            is LocalEstablishmentBindingVerifyResult.ModuleMismatch ->
                Profile01LocalEstablishmentIdentityAvailability.Unavailable("MODULE_MISMATCH")
            is LocalEstablishmentBindingVerifyResult.AlgorithmMismatch ->
                Profile01LocalEstablishmentIdentityAvailability.Unavailable("ALGORITHM_MISMATCH")
            is LocalEstablishmentBindingVerifyResult.VersionMismatch ->
                Profile01LocalEstablishmentIdentityAvailability.Unavailable("VERSION_MISMATCH")
            is LocalEstablishmentBindingVerifyResult.LocalEstablishmentKeyMismatch ->
                Profile01LocalEstablishmentIdentityAvailability.Unavailable("SPKI_MISMATCH")
            is LocalEstablishmentBindingVerifyResult.IdentityUnavailable ->
                Profile01LocalEstablishmentIdentityAvailability.Unavailable(verify.reason)
        }
    }
}
