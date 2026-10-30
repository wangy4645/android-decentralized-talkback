package com.talkback.core.session.gbc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0057 Track C — Acquisition send-on-locate wiring repair (ACQ-EG1..5).
 *
 * Narrow production wiring defect fix; GBC semantics unchanged.
 */
class Adr0057AcquisitionSendOnLocateFixturesTest {
    @Test
    fun acqEg1_requestFactWithNoHolders_defersAndKeepsObligationOpen() {
        val wiring = GroupBootstrapConvergenceWiring()
        val orch = orchestrator(wiring)
        wiring.observeAcquisitionIfUnresolved("CH-EG1", "cold_start")

        val plan =
            orch.planWireAttemptForRequestFact(
                channelId = "CH-EG1",
                correlation = "corr-eg1",
                localModuleId = "M01",
            )

        assertTrue(plan is AcquisitionWirePlan.DeferredNoHolder)
        assertEquals(ObligationState.OPEN, wiring.snapshot("CH-EG1").obligation)
        assertTrue(orch.wireAttemptedHolders("CH-EG1", "corr-eg1").isEmpty())
    }

    @Test
    fun acqEg2_helloLocateAfterDefer_producesSingleSendOpportunity() {
        val wiring = GroupBootstrapConvergenceWiring()
        val orch = orchestrator(wiring)
        wiring.observeAcquisitionIfUnresolved("CH-EG2", "cold_start")

        val deferred =
            orch.planWireAttemptForRequestFact("CH-EG2", "corr-eg2", localModuleId = "M01")
        assertTrue(deferred is AcquisitionWirePlan.DeferredNoHolder)

        val send =
            orch.planWireAttemptAfterHelloLocate(
                channelId = "CH-EG2",
                holderModuleId = "M02",
                localModuleId = "M01",
            )
        assertTrue(send is AcquisitionWirePlan.Send)
        send as AcquisitionWirePlan.Send
        assertEquals(listOf("M02"), send.holderModuleIds)
        assertEquals(ObligationState.OPEN, wiring.snapshot("CH-EG2").obligation)
    }

    @Test
    fun acqEg3_duplicateHelloSameHolder_doesNotBurst() {
        val wiring = GroupBootstrapConvergenceWiring()
        val orch = orchestrator(wiring)
        wiring.observeAcquisitionIfUnresolved("CH-EG3", "cold_start")
        orch.planWireAttemptForRequestFact("CH-EG3", "corr-eg3", localModuleId = "M01")

        val first =
            orch.planWireAttemptAfterHelloLocate("CH-EG3", "M02", localModuleId = "M01")
        assertTrue(first is AcquisitionWirePlan.Send)
        orch.markHoldersWireAttempted("CH-EG3", "corr-eg3", listOf("M02"))

        val duplicateLocate =
            orch.planWireAttemptAfterHelloLocate("CH-EG3", "M02", localModuleId = "M01")
        assertTrue(duplicateLocate is AcquisitionWirePlan.NoNewHolderLocate)

        val replan =
            orch.planWireAttemptForRequestFact("CH-EG3", "corr-eg3", localModuleId = "M01")
        assertTrue(replan is AcquisitionWirePlan.DeferredNoHolder)
    }

    @Test
    fun acqEg4_closedObligation_helloLocateDoesNotSend() {
        val wiring = GroupBootstrapConvergenceWiring()
        val orch = orchestrator(wiring)
        wiring.observeAcquisitionIfUnresolved("CH-EG4", "cold_start")
        val fact =
            AuthoritativeGenerationFact(
                generationIdentity = "G*",
                predecessorGenerationIdentity = null,
                originAuthorityIdentity = "M01",
                attestsCurrent = true,
                semanticDigest = "digest-gstar-eg4",
            )
        wiring.acceptVerifiedFact("CH-EG4", fact)
        assertEquals(ObligationState.CLOSED, wiring.snapshot("CH-EG4").obligation)

        val plan =
            orch.planWireAttemptAfterHelloLocate("CH-EG4", "M02", localModuleId = "M01")
        assertTrue(plan is AcquisitionWirePlan.ObligationClosed)
    }

    @Test
    fun acqEg5_existingGbcAndDeliveryRegressionUnchanged() {
        // A: cold start opens obligation + RequestFact semantics
        val gbc = GroupBootstrapConvergence("CH-EG5-A")
        val openFx = gbc.onAcquisitionRequired("cold_start")
        assertTrue(openFx.any { it is ConvergenceEffect.RequestFact })
        assertEquals(ObligationState.OPEN, gbc.snapshot().obligation)

        // V1: verify fail does not erase accepted Current
        val verifier = InjectableGenerationFactVerifier()
        val wiring = GroupBootstrapConvergenceWiring()
        val orch = FactDeliveryOrchestrator(wiring, VerificationBoundary(verifier))
        val good = candidate("G*", "digest-gstar-eg5", "good")
        verifier.stubOpaque("good", successFromCandidate(good))
        orch.onCandidateResponse("CH-EG5-V", "c1", good, admissiblePath = true)
        assertEquals(ObligationState.CLOSED, wiring.snapshot("CH-EG5-V").obligation)

        // V2: hello locate + selection still does not manufacture Current
        orch.onHelloLocate("CH-EG5-V2", "M02")
        val schedule = orch.scheduleAcquisition("CH-EG5-V2", "corr-eg5")
        assertEquals(listOf("M02"), schedule.selectedHolders)
        assertEquals(null, wiring.snapshot("CH-EG5-V2").acceptedCurrent)
    }

    private fun orchestrator(wiring: GroupBootstrapConvergenceWiring): FactDeliveryOrchestrator =
        FactDeliveryOrchestrator(wiring, VerificationBoundary(InjectableGenerationFactVerifier()))

    private fun candidate(
        generation: String,
        digest: String,
        opaque: String,
    ): GenerationFactCandidate =
        GenerationFactCandidate(
            claimedFactIdentity = digest,
            claimedGenerationIdentity = generation,
            claimedPredecessorGenerationIdentity = null,
            claimedOriginAuthorityIdentity = "M01",
            claimedAttestsCurrent = true,
            claimedSemanticDigest = digest,
            opaqueMaterial = opaque,
        )
}
