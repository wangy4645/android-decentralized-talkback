package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileRevisionDeliveryAcceptor
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreIdentityStore

/**
 * P1-A / EP production surface — establishment authority only; no package builder or wire emit.
 */
data class Profile01EstablishmentAuthoritySurface(
    val establishmentTrustStore: AcceptedLocalEstablishmentTrustStateStore,
    val recipientEstablishmentKeyLookup: Profile01RecipientEstablishmentKeyLookup,
    val localEstablishmentKeyVersion: () -> Long,
    val establishmentProfileDeliveryAcceptor: EstablishmentProfileRevisionDeliveryAcceptor,
    val verifiedLocalEstablishmentIdentity: () -> Profile01LocalEstablishmentIdentityAvailability,
)

object Profile01EstablishmentAuthorityWiring {
    fun create(
        establishmentTrustStore: AcceptedLocalEstablishmentTrustStateStore = AcceptedLocalEstablishmentTrustStateStore(),
        localModuleId: String,
        keystoreIdentityStore: LocalEstablishmentKeystoreIdentityStore,
        establishmentProfileDeliveryAcceptor: EstablishmentProfileRevisionDeliveryAcceptor,
    ): Profile01EstablishmentAuthoritySurface =
        compose(
            establishmentTrustStore = establishmentTrustStore,
            localModuleId = localModuleId,
            keystoreIdentityStore = keystoreIdentityStore,
            establishmentProfileDeliveryAcceptor = establishmentProfileDeliveryAcceptor,
        )

    internal fun compose(
        establishmentTrustStore: AcceptedLocalEstablishmentTrustStateStore,
        localModuleId: String,
        keystoreIdentityStore: LocalEstablishmentKeystoreIdentityStore,
        establishmentProfileDeliveryAcceptor: EstablishmentProfileRevisionDeliveryAcceptor,
    ): Profile01EstablishmentAuthoritySurface {
        val lookup = Profile01ProfileBackedRecipientEstablishmentKeyLookup(establishmentTrustStore)
        return Profile01EstablishmentAuthoritySurface(
            establishmentTrustStore = establishmentTrustStore,
            recipientEstablishmentKeyLookup = lookup,
            localEstablishmentKeyVersion = {
                Profile01LocalEstablishmentIdentity.activeEstablishmentKeyVersion(
                    establishmentTrustStore,
                    localModuleId,
                )
            },
            establishmentProfileDeliveryAcceptor = establishmentProfileDeliveryAcceptor,
            verifiedLocalEstablishmentIdentity = {
                Profile01VerifiedLocalEstablishmentIdentity.resolve(
                    establishmentTrustStore,
                    keystoreIdentityStore,
                    localModuleId,
                )
            },
        )
    }
}
