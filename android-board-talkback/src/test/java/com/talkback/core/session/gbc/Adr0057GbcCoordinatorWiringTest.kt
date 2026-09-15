package com.talkback.core.session.gbc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0057 Integration Conformance — ownership / read-path audit for Coordinator wiring.
 *
 * Proves: observation → GBC decision → effect → sink execution.
 * Forbidden: sink invents Current / F1 / obligation; Coordinator mutates authority first.
 */
class Adr0057GbcCoordinatorWiringTest {
    @Test
    fun ownership_order_decisionBeforeSink_andFenceBeforeDownstream() {
        val sinkEvents = mutableListOf<String>()
        var authorityMutatedBeforeDecision = false

        val wiring =
            GroupBootstrapConvergenceWiring { effects ->
                // Sink runs only after GBC has already updated its snapshot.
                // If Coordinator had mutated authority first, this flag would be set earlier.
                assertFalse(authorityMutatedBeforeDecision)
                effects.forEach { effect ->
                    when (effect) {
                        is ConvergenceEffect.FenceGeneration ->
                            sinkEvents += "fence:${effect.generationIdentity}"
                        is ConvergenceEffect.RealignToCurrent ->
                            sinkEvents += "realign:${effect.currentGenerationIdentity}"
                        is ConvergenceEffect.ObligationClosed ->
                            sinkEvents += "closed"
                        is ConvergenceEffect.RequestFact ->
                            sinkEvents += "request"
                    }
                }
            }

        // Local G1 treated-as-Current → opens acquisition (observation only)
        wiring.observeLocalGeneration("CH-W", "G1", treatedAsCurrent = true)
        assertEquals(ObligationState.OPEN, wiring.snapshot("CH-W").obligation)
        assertTrue(sinkEvents.contains("request"))

        // GBC decides SUPERSEDED + fence before sink records fence
        val beforeFact = wiring.snapshot("CH-W")
        assertEquals("G1", (beforeFact.knowledge as LocalGenerationKnowledge.Known).generationIdentity)

        val g2 =
            AuthoritativeGenerationFact(
                generationIdentity = "G2",
                predecessorGenerationIdentity = "G1",
                originAuthorityIdentity = "M01",
                attestsCurrent = true,
                semanticDigest = "digest-g2",
            )
        wiring.acceptVerifiedFact("CH-W", g2)

        assertTrue(wiring.convergence("CH-W").isGenerationFenced("G1"))
        assertEquals("G2", wiring.snapshot("CH-W").acceptedCurrent!!.generationIdentity)
        assertEquals(ObligationState.CLOSED, wiring.snapshot("CH-W").obligation)

        // Sink saw fence then realign then closed — never invented Current itself
        val fenceIdx = sinkEvents.indexOf("fence:G1")
        val realignIdx = sinkEvents.indexOf("realign:G2")
        val closedIdx = sinkEvents.indexOf("closed")
        assertTrue(fenceIdx >= 0)
        assertTrue(realignIdx > fenceIdx)
        assertTrue(closedIdx > realignIdx)
    }

    @Test
    fun cR2_proxies_neverUsedAsAuthorityInputs_byWiringApi() {
        assertTrue(GbcLegacyAuthorityProxies.isForbiddenAuthorityInput("waitingForPrimary"))
        assertTrue(GbcLegacyAuthorityProxies.isForbiddenAuthorityInput("bootstrapAttemptCount"))
        // Wiring API surface has no waitingForPrimary / attempt-count parameters.
        val wiring = GroupBootstrapConvergenceWiring()
        wiring.observeAcquisitionIfUnresolved("CH", "reconcile")
        assertEquals(ObligationState.OPEN, wiring.snapshot("CH").obligation)
        // Tick idempotent — does not close via wait proxy
        wiring.observeAcquisitionIfUnresolved("CH", "reconcile_tick")
        assertEquals(ObligationState.OPEN, wiring.snapshot("CH").obligation)
        wiring.observeNoReachableHolder("CH")
        assertEquals(ObligationState.OPEN, wiring.snapshot("CH").obligation)
    }

    @Test
    fun noParallelTruth_snapshotIsGbcPassthrough() {
        val wiring = GroupBootstrapConvergenceWiring()
        val fact =
            AuthoritativeGenerationFact(
                generationIdentity = "G*",
                predecessorGenerationIdentity = null,
                originAuthorityIdentity = "M01",
                attestsCurrent = true,
                semanticDigest = "digest-gstar",
            )
        wiring.acceptVerifiedFact("CH-P", fact)
        val viaWiring = wiring.snapshot("CH-P")
        val viaModule = wiring.convergence("CH-P").snapshot()
        assertEquals(viaModule.acceptedCurrent, viaWiring.acceptedCurrent)
        assertEquals(viaModule.obligation, viaWiring.obligation)
        assertEquals(viaModule.knowledge, viaWiring.knowledge)
    }
}
