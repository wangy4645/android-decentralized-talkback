package com.talkback.core.session.gbc.trust.profile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0057 PTI Layer B — Production Profile Authentication conformance (B-EG1..B-EG5).
 *
 * Uses PAP-T2-established anchor fixture input. Does NOT prove field operational provisioning.
 */
class Adr0057PtiLayerBFixturesTest {
    @Test
    fun b_eg1_productionAuthenticator_notFixture() {
        val harness = GenerationFactPtiLayerBFixtureSupport.layerBHarness()
        assertFalse(FixtureProfileRevisionAuthenticator::class.isInstance(harness.authenticator))
        assertTrue(ProductionProfileRevisionAuthenticator::class.isInstance(harness.authenticator))
    }

    @Test
    fun b_eg2_tamperedProtectedSemantics_authenticationRejected_preservesLastGood() {
        val h = GenerationFactPtiLayerBFixtureSupport.layerBHarness()
        val payload = GenerationFactPtiLayerBFixtureSupport.activeBindingPayloadV2(revision = 10)
        assertTrue(GenerationFactPtiLayerBFixtureSupport.acceptSigned(h, payload) is ProfileRevisionAcceptResult.Accepted)

        val signed = GenerationFactPtiLayerBFixtureSupport.signedDelivery(payload)
        val envelope = AuthenticatedProfileRevisionEnvelope.parse(signed)!!
        val tamperedProtected = envelope.protectedBytes.copyOf()
        tamperedProtected[10] = (tamperedProtected[10].toInt() xor 0xFF).toByte()
        val tamperedDelivery =
            AuthenticatedProfileRevisionEnvelope.build(tamperedProtected, envelope.signatureRs)

        val result = h.acceptor.acceptProtectedRevision(tamperedDelivery)
        assertTrue(result is ProfileRevisionAcceptResult.AuthenticationRejected)
        assertEquals(10L, h.store.currentSnapshot()?.taskProfileRevision)
    }

    @Test
    fun b_eg3_authenticatedOlderRevision_rollbackRejected() {
        val h = GenerationFactPtiLayerBFixtureSupport.layerBHarness()
        assertTrue(
            GenerationFactPtiLayerBFixtureSupport.acceptSigned(
                h,
                GenerationFactPtiLayerBFixtureSupport.activeBindingPayloadV2(revision = 20),
            ) is ProfileRevisionAcceptResult.Accepted,
        )
        val rollback =
            GenerationFactPtiLayerBFixtureSupport.activeBindingPayloadV2(revision = 10)
        val result = h.acceptor.acceptProtectedRevision(GenerationFactPtiLayerBFixtureSupport.signedDelivery(rollback))
        assertTrue(result is ProfileRevisionAcceptResult.RollbackRejected)
        assertEquals(20L, h.store.currentSnapshot()?.taskProfileRevision)
    }

    @Test
    fun b_eg4_sameRevisionDifferentSemantics_conflictRejected() {
        val h = GenerationFactPtiLayerBFixtureSupport.layerBHarness()
        val first = GenerationFactPtiLayerBFixtureSupport.activeBindingPayloadV2(revision = 10)
        assertTrue(GenerationFactPtiLayerBFixtureSupport.acceptSigned(h, first) is ProfileRevisionAcceptResult.Accepted)
        val conflicting =
            first.copy(
                localModuleId = "OTHER-MODULE",
            )
        val result =
            h.acceptor.acceptProtectedRevision(
                GenerationFactPtiLayerBFixtureSupport.signedDelivery(conflicting),
            )
        assertTrue(result is ProfileRevisionAcceptResult.RevisionConflictRejected)
        assertEquals(10L, h.store.currentSnapshot()?.taskProfileRevision)
    }

    @Test
    fun b_eg5_anchorBoundaryEvidence_preEstablishedImmutable() {
        val anchorBefore = GenerationFactPtiLayerBFixtureSupport.establishedAnchor()
        val store = PinnedOperationalTrustAnchorStore(anchorBefore)
        val authenticator =
            ProductionProfileRevisionAuthenticator(store, GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN)

        val wrongDomainPayload =
            GenerationFactPtiLayerBFixtureSupport.activeBindingPayloadV2().copy(
                deploymentTrustDomainId = GenerationFactPtiLayerBFixtureSupport.OTHER_TRUST_DOMAIN,
            )
        val wrongDomainResult =
            authenticator.authenticate(GenerationFactPtiLayerBFixtureSupport.signedDelivery(wrongDomainPayload))
        assertTrue(wrongDomainResult is ProfileAuthenticationResult.Rejected)

        val anchorAfter = store.establishedAnchor()
        assertEquals(anchorBefore.deploymentTrustDomainId, anchorAfter.deploymentTrustDomainId)
        assertArrayEquals(
            anchorBefore.operationalAuthorityPublicKeySpki,
            anchorAfter.operationalAuthorityPublicKeySpki,
        )

        assertNotNull(store.pinnedAnchor(GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN))
        assertEquals(null, store.pinnedAnchor(GenerationFactPtiLayerBFixtureSupport.OTHER_TRUST_DOMAIN))
    }

    @Test
    fun b_eg5_authenticatorDoesNotMutateAnchorOnReject() {
        val store = GenerationFactPtiLayerBFixtureSupport.anchorStore()
        val before = store.establishedAnchor()
        val authenticator = GenerationFactPtiLayerBFixtureSupport.productionAuthenticator(store)
        val unsigned = ProfileRevisionCanonicalCodec.encodeV2(GenerationFactPtiLayerBFixtureSupport.activeBindingPayloadV2())
        val rejected = authenticator.authenticate(unsigned)
        assertTrue(rejected is ProfileAuthenticationResult.Rejected)
        val after = store.establishedAnchor()
        assertArrayEquals(before.operationalAuthorityPublicKeySpki, after.operationalAuthorityPublicKeySpki)
    }

    @Test
    fun scope_wrongSignedScope_rejected() {
        val h = GenerationFactPtiLayerBFixtureSupport.layerBHarness()
        val payload =
            GenerationFactPtiLayerBFixtureSupport.activeBindingPayloadV2().copy(
                deploymentTrustDomainId = GenerationFactPtiLayerBFixtureSupport.OTHER_TRUST_DOMAIN,
            )
        val result = h.acceptor.acceptProtectedRevision(GenerationFactPtiLayerBFixtureSupport.signedDelivery(payload))
        assertTrue(result is ProfileRevisionAcceptResult.AuthenticationRejected)
    }

    @Test
    fun scope_rightSignedScope_wrongDomainAnchor_rejected() {
        val otherAnchor =
            OperationalTrustAnchor(
                deploymentTrustDomainId = GenerationFactPtiLayerBFixtureSupport.OTHER_TRUST_DOMAIN,
                operationalAuthorityPublicKeySpki = OperationalProfileTestSigning.publicKeySpki.copyOf(),
            )
        val store = PinnedOperationalTrustAnchorStore(otherAnchor)
        val authenticator =
            ProductionProfileRevisionAuthenticator(store, GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN)
        val payload = GenerationFactPtiLayerBFixtureSupport.activeBindingPayloadV2()
        val result = authenticator.authenticate(GenerationFactPtiLayerBFixtureSupport.signedDelivery(payload))
        assertTrue(result is ProfileAuthenticationResult.Rejected)
    }

    @Test
    fun schema_v1_notProductionAdmissible() {
        val v1Payload = GenerationFactPtiFixtureSupport.activeBindingPayload()
        val v1Protected = ProfileRevisionCanonicalCodec.encode(v1Payload)
        assertFalse(ProfileRevisionCanonicalCodec.isProductionAuthenticationAdmissible(v1Protected))
        val store = GenerationFactPtiLayerBFixtureSupport.anchorStore()
        val authenticator = GenerationFactPtiLayerBFixtureSupport.productionAuthenticator(store)
        val signedV1 =
            OperationalProfileTestSigning.signProtectedRevision(v1Protected)
        val result = authenticator.authenticate(signedV1)
        assertTrue(result is ProfileAuthenticationResult.Rejected)
    }

    @Test
    fun schema_v2_successPath_acceptsThroughLayerA() {
        val h = GenerationFactPtiLayerBFixtureSupport.layerBHarness()
        val payload = GenerationFactPtiLayerBFixtureSupport.activeBindingPayloadV2(revision = 15)
        val result = GenerationFactPtiLayerBFixtureSupport.acceptSigned(h, payload)
        assertTrue(result is ProfileRevisionAcceptResult.Accepted)
        val snap = h.store.currentSnapshot()
        assertEquals(15L, snap?.taskProfileRevision)
        assertEquals(GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN, snap?.deploymentTrustDomainId)
    }
}
