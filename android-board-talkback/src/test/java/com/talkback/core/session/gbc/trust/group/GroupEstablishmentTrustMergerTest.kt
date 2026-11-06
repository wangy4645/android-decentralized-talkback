package com.talkback.core.session.gbc.trust.group

import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentKeyLookupResult
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.TestLocalEstablishmentKeystoreIdentityStore
import com.talkback.core.session.gbc.wiring.GbcProductionTrustComposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class GroupEstablishmentTrustMergerTest {
    private val domain = GbcProductionTrustComposition.DEFAULT_DEPLOYMENT_TRUST_DOMAIN
    private val sharedSecret = "team-a-shared-secret"

    @Test
    fun twoPeers_exchangeVerifiedAdvertisements_hostLookupFound() {
        val keystoreA = TestLocalEstablishmentKeystoreIdentityStore()
        val keystoreB = TestLocalEstablishmentKeystoreIdentityStore()
        val identityA =
            keystoreA.provisionFirstIdentity("M01", 1L).let {
                (it as com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreProvisionResult.Created).identity
            }
        val identityB =
            keystoreB.provisionFirstIdentity("M02", 1L).let {
                (it as com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreProvisionResult.Created).identity
            }
        val storeA = AcceptedLocalEstablishmentTrustStateStore()
        val storeB = AcceptedLocalEstablishmentTrustStateStore()
        GroupEstablishmentTrustMerger.bootstrapLocalSnapshot(storeA, identityA, domain)
        GroupEstablishmentTrustMerger.bootstrapLocalSnapshot(storeB, identityB, domain)
        val macKey = GroupEstablishmentPsk.macKey(sharedSecret, domain)
        val nonceA = uuidNonce()
        val nonceB = uuidNonce()
        val adA = advertisement(identityA, nonceA, macKey)
        val adB = advertisement(identityB, nonceB, macKey)
        assertEquals(
            GroupEstablishmentTrustMerger.Outcome.Applied,
            GroupEstablishmentTrustMerger.applyVerifiedPeerAdvertisement(
                storeB,
                localModuleId = "M02",
                deploymentTrustDomainId = domain,
                macKey = macKey,
                advertisement = adA,
            ),
        )
        assertEquals(
            GroupEstablishmentTrustMerger.Outcome.Applied,
            GroupEstablishmentTrustMerger.applyVerifiedPeerAdvertisement(
                storeA,
                localModuleId = "M01",
                deploymentTrustDomainId = domain,
                macKey = macKey,
                advertisement = adB,
            ),
        )
        assertTrue(
            GroupEstablishmentTrustMerger.lookupPeer(storeA, "M02")
                is Profile01EstablishmentKeyLookupResult.Found,
        )
        assertTrue(
            GroupEstablishmentTrustMerger.lookupPeer(storeB, "M01")
                is Profile01EstablishmentKeyLookupResult.Found,
        )
    }

    @Test
    fun wrongSharedSecret_rejectsProof() {
        val keystore = TestLocalEstablishmentKeystoreIdentityStore()
        val local =
            keystore.provisionFirstIdentity("M01", 1L).let {
                (it as com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreProvisionResult.Created).identity
            }
        val store = AcceptedLocalEstablishmentTrustStateStore()
        GroupEstablishmentTrustMerger.bootstrapLocalSnapshot(store, local, domain)
        val ad =
            advertisement(
                local,
                uuidNonce(),
                GroupEstablishmentPsk.macKey("wrong-secret", domain),
            )
        val outcome =
            GroupEstablishmentTrustMerger.applyVerifiedPeerAdvertisement(
                store,
                localModuleId = "M02",
                deploymentTrustDomainId = domain,
                macKey = GroupEstablishmentPsk.macKey(sharedSecret, domain),
                advertisement = ad,
            )
        assertTrue(outcome is GroupEstablishmentTrustMerger.Outcome.Rejected)
    }

    private fun advertisement(
        identity: com.talkback.core.session.gbc.trust.profile.establishment.Profile01LocalEstablishmentIdentitySnapshot,
        nonceHex: String,
        macKey: ByteArray,
    ): GroupMemberEstablishmentAdvertisement {
        val proof =
            GroupMemberEstablishmentProof.sign(
                macKey = macKey,
                moduleId = identity.moduleId,
                establishmentKeyVersion = identity.establishmentKeyVersion,
                establishmentPublicKeySpki = identity.publicKeySpki,
                nonceHex = nonceHex,
            )
        return GroupMemberEstablishmentAdvertisement(
            moduleId = identity.moduleId,
            establishmentKeyVersion = identity.establishmentKeyVersion,
            establishmentPublicKeySpki = identity.publicKeySpki.copyOf(),
            nonceHex = nonceHex,
            proofHex = proof,
        )
    }

    private fun uuidNonce(): String = UUID.randomUUID().toString().replace("-", "")
}
