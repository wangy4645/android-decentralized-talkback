package com.talkback.core.session.gbc.wiring

import com.talkback.core.model.PredecessorWire
import com.talkback.core.session.gbc.FactDeliveryOrchestrator
import com.talkback.core.session.gbc.FactDeliveryResult
import com.talkback.core.session.gbc.GroupBootstrapConvergenceWiring
import com.talkback.core.session.gbc.InjectableGenerationFactVerifier
import com.talkback.core.session.gbc.ObligationState
import com.talkback.core.session.gbc.VerificationBoundary
import com.talkback.core.session.gbc.VerificationOutcome
import com.talkback.core.session.gbc.crypto.GenerationFactCanonicalCodec
import com.talkback.core.session.gbc.crypto.GenerationFactPv1FixtureSupport
import com.talkback.core.session.gbc.crypto.GenerationFactTestSigning
import com.talkback.core.session.gbc.crypto.ProductionGenerationFactVerifier
import com.talkback.core.session.gbc.trust.FixtureGenerationFactTrustLookup
import com.talkback.core.session.gbc.trust.profile.GenerationFactPtiLayerBFixtureSupport
import com.talkback.core.session.gbc.trust.profile.ProfileBackedGenerationFactTrustLookup
import com.talkback.core.session.gbc.trust.profile.ProfileRevisionAcceptResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0057 Production Verifier Wiring conformance (PW-EG1..PW-EG6).
 */
class Adr0057ProductionVerifierWiringFixturesTest {
    @Test
    fun pw_eg1_productionCompositionUsesRealAdapters() {
        val harness = ProductionVerifierWiringFixtureSupport.newPersistedHarness()
        assertTrue(harness.runtime.verifier is ProductionGenerationFactVerifier)
        assertTrue(harness.runtime.trustLookup is ProfileBackedGenerationFactTrustLookup)
        assertTrue(harness.productionWiring is ProductionGbcTrustWiring)
    }

    @Test
    fun pw_eg2_productionGraphHasNoInjectableFallback() {
        val harness = ProductionVerifierWiringFixtureSupport.newPersistedHarness()
        assertFalse(InjectableGenerationFactVerifier::class.isInstance(harness.runtime.verifier))
        assertFalse(FixtureGenerationFactTrustLookup::class.isInstance(harness.runtime.trustLookup))
    }

    @Test
    fun pw_eg3_trustNotReady_thenReady_sameCandidateMayVerify() {
        val harness = ProductionVerifierWiringFixtureSupport.newPersistedHarness()
        val boundary = harness.productionWiring.verificationBoundary
        val wiring = GroupBootstrapConvergenceWiring()
        val orch = FactDeliveryOrchestrator(wiring, boundary)
        val candidate = signedCandidate("G-PW3")

        wiring.observeAcquisitionIfUnresolved("CH-PW3", "cold")

        val t0 = orch.onCandidateResponse("CH-PW3", "c0", candidate, admissiblePath = true)
        assertTrue(t0 is FactDeliveryResult.NotPromoted)
        assertEquals(VerificationOutcome.Unverifiable, (t0 as FactDeliveryResult.NotPromoted).outcome)
        assertNull(wiring.snapshot("CH-PW3").acceptedCurrent)
        assertEquals(ObligationState.OPEN, wiring.snapshot("CH-PW3").obligation)

        val payload = GenerationFactPtiLayerBFixtureSupport.activeBindingPayloadV2()
        val accept =
            harness.runtime.profileAcceptor.acceptProtectedRevision(
                GenerationFactPtiLayerBFixtureSupport.signedDelivery(payload),
            )
        assertTrue(accept is ProfileRevisionAcceptResult.Accepted)

        val t2 = orch.onCandidateResponse("CH-PW3", "c2", candidate, admissiblePath = true)
        assertTrue(t2 is FactDeliveryResult.VerifiedAccepted)
        assertEquals("G-PW3", wiring.snapshot("CH-PW3").acceptedCurrent!!.generationIdentity)
        assertEquals(ObligationState.CLOSED, wiring.snapshot("CH-PW3").obligation)
    }

    @Test
    fun pw_eg4_verifyFailuresDoNotEraseGoodCurrent() {
        val harness = ProductionVerifierWiringFixtureSupport.newPersistedHarness()
        acceptProfile(harness)
        val boundary = harness.productionWiring.verificationBoundary
        val wiring = GroupBootstrapConvergenceWiring()
        val orch = FactDeliveryOrchestrator(wiring, boundary)

        val good = signedCandidate("G-GOOD")
        assertTrue(orch.onCandidateResponse("CH-PW4", "c1", good, true) is FactDeliveryResult.VerifiedAccepted)
        assertEquals("G-GOOD", wiring.snapshot("CH-PW4").acceptedCurrent!!.generationIdentity)

        val badAuthority =
            GenerationFactCanonicalCodec.AuthoritySemantics(
                generationIdentity = "G-BAD",
                predecessor = PredecessorWire.None,
                originAuthorityIdentity = GenerationFactPv1FixtureSupport.MODULE_M01,
                attestsCurrent = true,
            )
        val bad =
            GenerationFactTestSigning.buildCandidate(
                authority = badAuthority,
                signerKeyVersion = 99L,
                trustBindingRevision = GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        val failed = orch.onCandidateResponse("CH-PW4", "c2", bad, true)
        assertTrue(failed is FactDeliveryResult.NotPromoted)
        assertEquals(VerificationOutcome.Unverifiable, (failed as FactDeliveryResult.NotPromoted).outcome)
        assertEquals("G-GOOD", wiring.snapshot("CH-PW4").acceptedCurrent!!.generationIdentity)
        assertEquals(ObligationState.CLOSED, wiring.snapshot("CH-PW4").obligation)
    }

    @Test
    fun pw_eg5_singlePromotionSeam_onlyBoundaryProducesAuthoritativeFact() {
        val harness = ProductionVerifierWiringFixtureSupport.newPersistedHarness()
        acceptProfile(harness)
        val outcome =
            harness.productionWiring.verificationBoundary.admit(signedCandidate("G-PROMO"))
        assertTrue(outcome is VerificationOutcome.Success)
        val fact = (outcome as VerificationOutcome.Success).fact
        val wiring = GroupBootstrapConvergenceWiring()
        val orch = FactDeliveryOrchestrator(wiring, harness.productionWiring.verificationBoundary)
        val delivery = orch.onCandidateResponse("CH-PW5", "c1", signedCandidate("G-PROMO"), true)
        assertTrue(delivery is FactDeliveryResult.VerifiedAccepted)
        assertEquals(fact.semanticDigest, (delivery as FactDeliveryResult.VerifiedAccepted).fact.semanticDigest)
    }

    @Test
    fun pw_eg6_persistedAnchorToGbc_fullProductionHappyPath() {
        val harness = ProductionVerifierWiringFixtureSupport.newPersistedHarness()
        acceptProfile(harness)
        val wiring = GroupBootstrapConvergenceWiring()
        val orch = harness.productionWiring.factDeliveryOrchestrator(wiring)
        val candidate = signedCandidate("G-E2E")
        val result = orch.onCandidateResponse("CH-E2E", "corr", candidate, admissiblePath = true)
        assertTrue(result is FactDeliveryResult.VerifiedAccepted)
        assertEquals("G-E2E", wiring.snapshot("CH-E2E").acceptedCurrent!!.generationIdentity)
        assertEquals(ObligationState.CLOSED, wiring.snapshot("CH-E2E").obligation)
    }

    @Test
    fun pw_ia_t2_testCompositionRootRemainsSeparate() {
        val testWiring = TestGbcTrustWiring()
        assertTrue(testWiring.injectableVerifier is InjectableGenerationFactVerifier)
        val production = ProductionVerifierWiringFixtureSupport.newPersistedHarness().productionWiring
        assertTrue(production is ProductionGbcTrustWiring)
    }

    private fun acceptProfile(harness: ProductionVerifierWiringFixtureSupport.PersistedHarness) {
        val payload = GenerationFactPtiLayerBFixtureSupport.activeBindingPayloadV2()
        val result =
            harness.runtime.profileAcceptor.acceptProtectedRevision(
                GenerationFactPtiLayerBFixtureSupport.signedDelivery(payload),
            )
        assertTrue(result is ProfileRevisionAcceptResult.Accepted)
    }

    private fun signedCandidate(generationIdentity: String) =
        GenerationFactTestSigning.buildCandidate(
            authority =
                GenerationFactCanonicalCodec.AuthoritySemantics(
                    generationIdentity = generationIdentity,
                    predecessor = PredecessorWire.None,
                    originAuthorityIdentity = GenerationFactPv1FixtureSupport.MODULE_M01,
                    attestsCurrent = true,
                ),
            signerKeyVersion = GenerationFactPv1FixtureSupport.KEY_VERSION,
            trustBindingRevision = GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
        )
}
