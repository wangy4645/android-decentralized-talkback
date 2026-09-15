package com.talkback.core.conference.session.profile01.wire

/**
 * PR-P1D-2 production MEDIA_KEY_PACKAGE decrypt composition.
 *
 * Verified local establishment identity → production unwrap seam → decrypt seam.
 * Unavailable identity → null (fail-closed; no stub decrypt seam).
 */
internal object Profile01ProductionMediaKeyDecryptComposition {
    fun compose(
        establishmentAuthority: Profile01EstablishmentAuthoritySurface,
        keyOperation: EstablishmentPekUnwrapKeyOperation =
            AndroidKeystoreEstablishmentPekUnwrapKeyOperation(),
    ): Profile01MediaKeyPackageDecryptSeam? {
        val recipientSeam =
            Profile01ProductionRecipientKeyEstablishmentSeam.create(
                establishmentAuthority.verifiedLocalEstablishmentIdentity(),
                keyOperation,
            ) ?: return null
        return Profile01MediaKeyPackageDecryptSeam(recipientSeam)
    }
}
