package com.talkback.core.session.gbc.wiring

import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentAuthoritySurface
import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentAuthorityWiring
import com.talkback.core.conference.session.profile01.wire.Profile01LocalEstablishmentIdentityAvailability
import com.talkback.core.conference.session.profile01.wire.Profile01VerifiedLocalEstablishmentIdentity
import com.talkback.core.session.gbc.trust.profile.OperationalTrustAnchorSource
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedEstablishmentTrustStatePersistence
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.AndroidKeystoreLocalEstablishmentIdentityStore
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileOperationalAuthenticator
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileRevisionAcceptor
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileRevisionDeliveryAcceptor
import com.talkback.core.session.gbc.trust.profile.establishment.FileAcceptedEstablishmentTrustStatePersistence
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreIdentityStore
import java.nio.file.Path

/**
 * EP Production Wiring composition root.
 *
 * Consumes authenticated establishment trust state and assembles authority surfaces.
 * Does not enroll, generate keys, or accept trust without explicit signed delivery.
 */
object EstablishmentProductionComposition {
    data class EstablishmentProductionRuntime(
        val deploymentTrustDomainId: String,
        val establishmentTrustStore: AcceptedLocalEstablishmentTrustStateStore,
        val establishmentProfileDeliveryAcceptor: EstablishmentProfileRevisionDeliveryAcceptor,
        val keystoreIdentityStore: LocalEstablishmentKeystoreIdentityStore,
        val authoritySurface: Profile01EstablishmentAuthoritySurface,
    )

    fun create(
        deploymentTrustDomainId: String,
        anchorSource: OperationalTrustAnchorSource,
        localModuleId: String,
        establishmentStateFile: Path? = null,
        keystoreStore: LocalEstablishmentKeystoreIdentityStore = AndroidKeystoreLocalEstablishmentIdentityStore(),
        establishmentTrustStore: AcceptedLocalEstablishmentTrustStateStore? = null,
    ): EstablishmentProductionRuntime {
        require(deploymentTrustDomainId.isNotBlank()) { "deploymentTrustDomainId required" }
        require(localModuleId.isNotBlank()) { "localModuleId required" }
        val persistence: AcceptedEstablishmentTrustStatePersistence? =
            establishmentStateFile?.let { FileAcceptedEstablishmentTrustStatePersistence(it) }
        val store = establishmentTrustStore ?: AcceptedLocalEstablishmentTrustStateStore(persistence)
        val authenticator =
            EstablishmentProfileOperationalAuthenticator(anchorSource, deploymentTrustDomainId)
        val acceptor = EstablishmentProfileRevisionAcceptor(store)
        val deliveryAcceptor = EstablishmentProfileRevisionDeliveryAcceptor(authenticator, acceptor)
        val authoritySurface =
            Profile01EstablishmentAuthorityWiring.compose(
                establishmentTrustStore = store,
                localModuleId = localModuleId,
                keystoreIdentityStore = keystoreStore,
                establishmentProfileDeliveryAcceptor = deliveryAcceptor,
            )
        return EstablishmentProductionRuntime(
            deploymentTrustDomainId = deploymentTrustDomainId,
            establishmentTrustStore = store,
            establishmentProfileDeliveryAcceptor = deliveryAcceptor,
            keystoreIdentityStore = keystoreStore,
            authoritySurface = authoritySurface,
        )
    }
}
