package com.talkback.core.session.gbc.wiring

import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentAuthorityWiring
import com.talkback.core.session.gbc.trust.profile.OperationalTrustAnchorSource
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileOperationalAuthenticator
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileRevisionAcceptor
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileRevisionDeliveryAcceptor
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreIdentityStore
import com.talkback.core.session.gbc.trust.profile.establishment.TestLocalEstablishmentKeystoreIdentityStore
import java.nio.file.Path

/**
 * Harness-only establishment production wiring for unit tests (W1–W7).
 */
object EstablishmentProductionWiringHarness {
    fun create(
        deploymentTrustDomainId: String,
        anchorSource: OperationalTrustAnchorSource,
        localModuleId: String,
        keystoreStore: LocalEstablishmentKeystoreIdentityStore = TestLocalEstablishmentKeystoreIdentityStore(),
        establishmentStateFile: Path? = null,
        establishmentTrustStore: AcceptedLocalEstablishmentTrustStateStore? = null,
    ): EstablishmentProductionComposition.EstablishmentProductionRuntime =
        EstablishmentProductionComposition.create(
            deploymentTrustDomainId = deploymentTrustDomainId,
            anchorSource = anchorSource,
            localModuleId = localModuleId,
            establishmentStateFile = establishmentStateFile,
            keystoreStore = keystoreStore,
            establishmentTrustStore = establishmentTrustStore,
        )

    fun deliveryAcceptorOnly(
        deploymentTrustDomainId: String,
        anchorSource: OperationalTrustAnchorSource,
        store: AcceptedLocalEstablishmentTrustStateStore = AcceptedLocalEstablishmentTrustStateStore(),
    ): EstablishmentProfileRevisionDeliveryAcceptor {
        val authenticator = EstablishmentProfileOperationalAuthenticator(anchorSource, deploymentTrustDomainId)
        return EstablishmentProfileRevisionDeliveryAcceptor(
            authenticator,
            EstablishmentProfileRevisionAcceptor(store),
        )
    }

    fun authoritySurface(
        localModuleId: String,
        keystoreStore: LocalEstablishmentKeystoreIdentityStore,
        store: AcceptedLocalEstablishmentTrustStateStore,
        deliveryAcceptor: EstablishmentProfileRevisionDeliveryAcceptor,
    ) = Profile01EstablishmentAuthorityWiring.create(
        establishmentTrustStore = store,
        localModuleId = localModuleId,
        keystoreIdentityStore = keystoreStore,
        establishmentProfileDeliveryAcceptor = deliveryAcceptor,
    )
}
