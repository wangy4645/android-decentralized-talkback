package com.talkback.core.session.gbc.trust.group

import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentKeyLookupResult
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.TestLocalEstablishmentKeystoreIdentityStore
import com.talkback.core.session.gbc.wiring.GbcProductionTrustComposition
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEstablishmentHelloTrustServiceTest {
    private val domain = GbcProductionTrustComposition.DEFAULT_DEPLOYMENT_TRUST_DOMAIN
    private val sharedSecret = "team-a-shared-secret"

    @Test
    fun helloExchange_materializesPeerBindingWithoutTofu() {
        val keystoreA = TestLocalEstablishmentKeystoreIdentityStore()
        val keystoreB = TestLocalEstablishmentKeystoreIdentityStore()
        val storeA = AcceptedLocalEstablishmentTrustStateStore()
        val storeB = AcceptedLocalEstablishmentTrustStateStore()
        val serviceA =
            GroupEstablishmentHelloTrustService(
                signingTrustStore = com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore(),
                establishmentTrustStore = storeA,
                keystoreStore = keystoreA,
                localModuleId = "M01",
                deploymentTrustDomainId = domain,
                sharedSecret = sharedSecret,
            )
        val serviceB =
            GroupEstablishmentHelloTrustService(
                signingTrustStore = com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore(),
                establishmentTrustStore = storeB,
                keystoreStore = keystoreB,
                localModuleId = "M02",
                deploymentTrustDomainId = domain,
                sharedSecret = sharedSecret,
            )
        serviceA.onRuntimeAttached()
        serviceB.onRuntimeAttached()
        val digestA = serviceA.buildLocalHelloDigest()
        val digestB = serviceB.buildLocalHelloDigest()
        requireNotNull(digestA)
        requireNotNull(digestB)
        assertTrue(digestA.hasGkeEstablishmentProof())
        assertTrue(digestB.hasGkeEstablishmentProof())
        val outcomeB =
            serviceB.applyFromHello("M01", digestA) as
                GroupEstablishmentHelloTrustService.HelloApplyOutcome.GkeApplied
        val outcomeA =
            serviceA.applyFromHello("M02", digestB) as
                GroupEstablishmentHelloTrustService.HelloApplyOutcome.GkeApplied
        assertTrue(outcomeA.detail.isNotBlank())
        assertTrue(outcomeB.detail.isNotBlank())
        assertTrue(
            GroupEstablishmentTrustMerger.lookupPeer(storeA, "M02")
                is Profile01EstablishmentKeyLookupResult.Found,
        )
        assertTrue(
            GroupEstablishmentTrustMerger.lookupPeer(storeB, "M01")
                is Profile01EstablishmentKeyLookupResult.Found,
        )
    }
}
