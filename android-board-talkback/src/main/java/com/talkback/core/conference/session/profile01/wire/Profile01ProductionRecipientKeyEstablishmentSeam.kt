package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.session.gbc.trust.profile.establishment.Profile01LocalEstablishmentIdentitySnapshot

/**
 * PR-P1D-1 production PEK unwrap seam.
 *
 * Unwrap authority flows from accepted local establishment binding + verified Keystore identity.
 * Wire version/module are compared against the verified snapshot only — never used to resolve
 * Keystore aliases.
 */
class Profile01ProductionRecipientKeyEstablishmentSeam private constructor(
    private val verifiedIdentity: Profile01LocalEstablishmentIdentitySnapshot,
    private val keyOperation: EstablishmentPekUnwrapKeyOperation,
) : Profile01RecipientKeyEstablishmentSeam {
    override fun unwrapPek(
        wrappedPek: ByteArray,
        recipientModuleId: String,
        recipientKeyVersion: Long,
    ): Profile01PekUnwrapResult {
        if (recipientModuleId != verifiedIdentity.moduleId) {
            return Profile01PekUnwrapResult.Rejected("RECIPIENT_MODULE_MISMATCH")
        }
        if (recipientKeyVersion != verifiedIdentity.establishmentKeyVersion) {
            return Profile01PekUnwrapResult.Rejected("RECIPIENT_KEY_VERSION_MISMATCH")
        }
        return keyOperation.unwrap(wrappedPek, verifiedIdentity.keyAlias)
    }

    fun verifiedIdentitySnapshot(): Profile01LocalEstablishmentIdentitySnapshot =
        verifiedIdentity.copy(
            publicKeySpki = verifiedIdentity.publicKeySpki.copyOf(),
        )

    companion object {
        fun create(
            availability: Profile01LocalEstablishmentIdentityAvailability,
            keyOperation: EstablishmentPekUnwrapKeyOperation =
                AndroidKeystoreEstablishmentPekUnwrapKeyOperation(),
        ): Profile01ProductionRecipientKeyEstablishmentSeam? =
            when (availability) {
                is Profile01LocalEstablishmentIdentityAvailability.Verified ->
                    Profile01ProductionRecipientKeyEstablishmentSeam(
                        verifiedIdentity = availability.identity,
                        keyOperation = keyOperation,
                    )
                is Profile01LocalEstablishmentIdentityAvailability.Unavailable -> null
            }
    }
}
