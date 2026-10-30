package com.talkback.core.session.gbc.origin

import com.talkback.core.session.gbc.FactDeliveryOrchestrator
import com.talkback.core.session.gbc.GroupBootstrapConvergenceWiring
import com.talkback.core.session.gbc.ObligationState
import com.talkback.core.session.gbc.ReprovideResult
import com.talkback.core.session.gbc.VerificationBoundary
import com.talkback.core.session.gbc.crypto.GenerationFactCanonicalCodec
import com.talkback.core.session.gbc.crypto.GenerationFactPv1FixtureSupport
import com.talkback.core.session.gbc.crypto.GenerationFactTestSigning
import com.talkback.core.session.gbc.crypto.ProductionGenerationFactVerifier
import com.talkback.core.session.gbc.issuance.GenerationFactIssuanceSigner
import com.talkback.core.session.gbc.wiring.GbcOriginComposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * ADR-0057 CSO-EG1..EG6 — cold-start origin production seam fixtures.
 */
class Adr0057ColdStartOriginFixturesTest {
    @Test
    fun csoEg1_candidateWithNoCurrent_issuesExactlyOneGenesis() {
        val h = harness("M01")
        h.wiring.observeAcquisitionIfUnresolved("CH-EG1", "cold_start")

        val first =
            h.cso.attemptColdStartOriginIfEligible("CH-EG1", "M01", "M01")
        assertTrue(first is ColdStartOriginAttemptResult.Issued)

        val second =
            h.cso.attemptColdStartOriginIfEligible("CH-EG1", "M01", "M01")
        assertTrue(second is ColdStartOriginAttemptResult.Skipped)

        assertEquals(1, h.originRuntime.issuanceStore.finalizedRecords(h.originRuntime.issuanceKey).size)
    }

    @Test
    fun csoEg2_followerDoesNotOriginate() {
        val h = harness("M02")
        h.wiring.observeAcquisitionIfUnresolved("CH-EG2", "cold_start")

        val result =
            h.cso.attemptColdStartOriginIfEligible("CH-EG2", "M01", "M02")
        assertTrue(result is ColdStartOriginAttemptResult.Skipped)
        assertTrue(
            (result as ColdStartOriginAttemptResult.Skipped).reason is
                ColdStartOriginEligibility.NotEligible.NotBootstrapCandidate,
        )
        assertEquals(null, h.wiring.snapshot("CH-EG2").acceptedCurrent)
    }

    @Test
    fun csoEg3_duplicateReevaluationDoesNotBurstGenesis() {
        val h = harness("M01")
        h.wiring.observeAcquisitionIfUnresolved("CH-EG3", "cold_start")

        repeat(3) {
            h.cso.attemptColdStartOriginIfEligible("CH-EG3", "M01", "M01")
        }
        assertEquals(1, h.originRuntime.issuanceStore.finalizedRecords(h.originRuntime.issuanceKey).size)
    }

    @Test
    fun csoEg4_localGbcRetainsCurrentAfterPv2Finalize() {
        val h = harness("M01")
        h.wiring.observeAcquisitionIfUnresolved("CH-EG4", "cold_start")

        val issued =
            h.cso.attemptColdStartOriginIfEligible("CH-EG4", "M01", "M01")
        assertTrue(issued is ColdStartOriginAttemptResult.Issued)

        val snap = h.wiring.snapshot("CH-EG4")
        assertNotNull(snap.acceptedCurrent)
        assertEquals(ObligationState.CLOSED, snap.obligation)
        assertEquals(
            (issued as ColdStartOriginAttemptResult.Issued).generationIdentity,
            snap.acceptedCurrent!!.generationIdentity,
        )
    }

    @Test
    fun csoEg5_originReprovidesExactRetainedFact() {
        val holder = harness("M01")
        holder.wiring.observeAcquisitionIfUnresolved("CH-EG5", "cold_start")
        val issued =
            holder.cso.attemptColdStartOriginIfEligible("CH-EG5", "M01", "M01")
        assertTrue(issued is ColdStartOriginAttemptResult.Issued)

        val reprovide = holder.orch.onAuthorizedReprovideRequest("CH-EG5")
        assertTrue(reprovide is ReprovideResult.Fact)
        val fact = (reprovide as ReprovideResult.Fact).fact
        assertEquals(
            holder.wiring.snapshot("CH-EG5").acceptedCurrent!!.semanticDigest,
            fact.semanticDigest,
        )
        assertTrue(reprovide.verificationMaterial.isNotBlank())
    }

    @Test
    fun csoEg6_restartRestoresWithoutSecondGenesis() {
        val tmp = Files.createTempDirectory("cso-eg6")
        val first = harness("M01", tmp)
        first.wiring.observeAcquisitionIfUnresolved("CH-EG6", "cold_start")
        val issued =
            first.cso.attemptColdStartOriginIfEligible("CH-EG6", "M01", "M01")
        assertTrue(issued is ColdStartOriginAttemptResult.Issued)
        val genesisId = (issued as ColdStartOriginAttemptResult.Issued).generationIdentity

        val restarted = harness("M01", tmp)
        restarted.wiring.observeAcquisitionIfUnresolved("CH-EG6", "cold_start")
        val restored =
            restarted.cso.restoreColdStartOriginIfNeeded("CH-EG6", "M01", "M01")
        assertTrue(restored is ColdStartOriginAttemptResult.Restored)
        assertEquals(genesisId, restarted.wiring.snapshot("CH-EG6").acceptedCurrent!!.generationIdentity)
        assertEquals(1, restarted.originRuntime.issuanceStore.finalizedRecords(restarted.originRuntime.issuanceKey).size)
    }

    private fun harness(
        localModuleId: String,
        persistenceRoot: java.nio.file.Path? = null,
    ): CsoHarness {
        val wiring = GroupBootstrapConvergenceWiring()
        val verifier = ProductionGenerationFactVerifier(GenerationFactPv1FixtureSupport.activeTrustLookup())
        val boundary = VerificationBoundary(verifier)
        val orch = FactDeliveryOrchestrator(wiring, boundary)
        val signer =
            GenerationFactIssuanceSigner { authorityBytes, contextBytes ->
                GenerationFactTestSigning.signMessage(
                    GenerationFactCanonicalCodec.signatureInput(authorityBytes, contextBytes),
                )
            }
        val originRuntime =
            GbcOriginComposition.createTestOriginRuntime(
                localModuleId = localModuleId,
                signer = signer,
                signerKeyVersion = GenerationFactPv1FixtureSupport.KEY_VERSION,
                trustBindingRevision = GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
                persistenceRoot = persistenceRoot,
            )
        val cso = originRuntime.createOrchestrator(wiring, orch, boundary)
        return CsoHarness(wiring, orch, originRuntime, cso)
    }

    private data class CsoHarness(
        val wiring: GroupBootstrapConvergenceWiring,
        val orch: FactDeliveryOrchestrator,
        val originRuntime: GbcOriginComposition.GbcOriginRuntime,
        val cso: ColdStartOriginOrchestrator,
    )
}
