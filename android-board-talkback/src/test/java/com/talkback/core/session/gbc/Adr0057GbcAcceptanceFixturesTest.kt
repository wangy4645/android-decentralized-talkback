package com.talkback.core.session.gbc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0057 A–E acceptance fixtures (R3-HARD).
 * Synthetic verified Fact ingress only — no production wire/crypto.
 *
 * Harness MUST NOT fake F1 / obligation / fence / convergence conclusions.
 */
class Adr0057GbcAcceptanceFixturesTest {
    @Test
    fun a_coldStart_unknownToCurrent_obligationCloses() {
        val gbc = GroupBootstrapConvergence("CH-A")
        // Pre-gap evidence: no Fact → obligation opens; not closed by wait/retry proxies
        assertTrue(GbcLegacyAuthorityProxies.isForbiddenAuthorityInput("waitingForPrimary"))
        val openFx = gbc.onAcquisitionRequired("cold_start")
        assertTrue(gbc.snapshot().obligation == ObligationState.OPEN)
        assertTrue(openFx.any { it is ConvergenceEffect.RequestFact })

        val gStar = currentFact("G*", predecessor = null)
        val fx = gbc.acceptVerifiedFact(gStar)

        val snap = gbc.snapshot()
        assertNotNull(snap.acceptedCurrent)
        assertEquals("G*", snap.acceptedCurrent!!.generationIdentity)
        assertEquals(ObligationState.CLOSED, snap.obligation)
        assertTrue(fx.any { it is ConvergenceEffect.RealignToCurrent })
        assertTrue(fx.any { it is ConvergenceEffect.ObligationClosed })
        // Leave UNKNOWN gating for this obligation
        assertTrue(snap.knowledge is LocalGenerationKnowledge.Known)

        // Subsequent F1 with L = G* → SAME (not a new F1 state for adoption)
        val relation =
            GenerationClassifier.classify(
                LocalGenerationKnowledge.Known("G*", treatedAsCurrent = true),
                gStar,
            )
        assertEquals(FactRelation.SAME, relation)
    }

    @Test
    fun b_staleFollower_g1Superseded_fence_g2Current_closed() {
        val gbc = GroupBootstrapConvergence("CH-B")
        gbc.onLocalGenerationObserved("G1", treatedAsCurrent = true)
        gbc.onAcquisitionRequired("verify_local")

        val g2 = currentFact("G2", predecessor = "G1")
        val fx = gbc.acceptVerifiedFact(g2)

        assertEquals(FactRelation.SUPERSEDED, gbc.snapshot().lastRelation)
        assertTrue(gbc.isGenerationFenced("G1"))
        assertTrue(fx.any { it is ConvergenceEffect.FenceGeneration && it.generationIdentity == "G1" })
        assertEquals("G2", gbc.snapshot().acceptedCurrent!!.generationIdentity)
        assertEquals(
            "G2",
            (gbc.snapshot().knowledge as LocalGenerationKnowledge.Known).generationIdentity,
        )
        assertEquals(ObligationState.CLOSED, gbc.snapshot().obligation)
        assertFalse(gbc.isGenerationFenced("G2"))
    }

    @Test
    fun b_supersededAloneDoesNotCloseWithoutCurrent() {
        val gbc = GroupBootstrapConvergence("CH-B2")
        gbc.onLocalGenerationObserved("G1", treatedAsCurrent = true)
        gbc.onAcquisitionRequired("verify_local")
        // Fact that orders supersession but does not attest Current (edge) —
        // under our Fact model attestsCurrent is required for Current; use classify path:
        // If attestsCurrent=true with predecessor, L3 requires usable Current which we have.
        // Explicit: obligation stays open if we only fence without retaining Current.
        val nonCurrent =
            AuthoritativeGenerationFact(
                generationIdentity = "G2",
                predecessorGenerationIdentity = "G1",
                originAuthorityIdentity = "M01",
                attestsCurrent = false,
                semanticDigest = "digest-g2-noncurrent",
            )
        gbc.acceptVerifiedFact(nonCurrent)
        assertTrue(gbc.snapshot().obligation == ObligationState.OPEN)
    }

    @Test
    fun c_noReachableHolder_obligationRemainsOpen_noOriginate() {
        val gbc = GroupBootstrapConvergence("CH-C")
        gbc.onAcquisitionRequired("cold_start")
        assertEquals(ObligationState.OPEN, gbc.snapshot().obligation)

        gbc.onNoReachableHolderOrAttemptTerminal()
        assertEquals(ObligationState.OPEN, gbc.snapshot().obligation)
        assertTrue(gbc.isOriginateForbiddenWhileUnknown())
        assertTrue(gbc.snapshot().knowledge is LocalGenerationKnowledge.Unknown)
        assertTrue(gbc.snapshot().acceptedCurrent == null)
    }

    @Test
    fun d_lateFact_expiredCorrelationDoesNotKillAuthority_whenAdmissiblePath() {
        val gbc = GroupBootstrapConvergence("CH-D")
        gbc.onAcquisitionRequired("cold_start")
        gbc.beginAcquisitionExchange("C1")
        gbc.expireAcquisitionExchange("C1")

        val gStar = currentFact("G*", predecessor = null)
        // Expired exchange bookkeeping: not admissible via C1 alone
        val ignored =
            gbc.onFactResponse(
                correlation = "C1",
                outcome = FactResponseOutcome.Fact(gStar),
                admissiblePath = false,
            )
        assertTrue(ignored.isEmpty())
        assertEquals(ObligationState.OPEN, gbc.snapshot().obligation)

        // Independently admissible path (e.g. another holder / new exchange)
        val fx =
            gbc.onFactResponse(
                correlation = "C2",
                outcome = FactResponseOutcome.Fact(gStar),
                admissiblePath = true,
            )
        assertEquals(ObligationState.CLOSED, gbc.snapshot().obligation)
        assertEquals("G*", gbc.snapshot().acceptedCurrent!!.generationIdentity)
        assertTrue(fx.any { it is ConvergenceEffect.ObligationClosed })
    }

    @Test
    fun e_lateInsufficient_doesNotRegressAcceptedCurrent() {
        val gbc = GroupBootstrapConvergence("CH-E")
        gbc.onAcquisitionRequired("cold_start")
        gbc.acceptVerifiedFact(currentFact("G*", predecessor = null))
        assertEquals(ObligationState.CLOSED, gbc.snapshot().obligation)
        assertEquals("G*", gbc.snapshot().acceptedCurrent!!.generationIdentity)

        gbc.onFactResponse(
            correlation = "late",
            outcome = FactResponseOutcome.Insufficient,
            admissiblePath = true,
        )
        assertEquals(ObligationState.CLOSED, gbc.snapshot().obligation)
        assertEquals("G*", gbc.snapshot().acceptedCurrent!!.generationIdentity)
        assertTrue(gbc.snapshot().knowledge is LocalGenerationKnowledge.Known)
    }

    @Test
    fun m3_duplicateFact_idempotentNoRepeatFence() {
        val gbc = GroupBootstrapConvergence("CH-M3")
        gbc.onLocalGenerationObserved("G1", treatedAsCurrent = true)
        val g2 = currentFact("G2", predecessor = "G1")
        val first = gbc.acceptVerifiedFact(g2)
        val fenceCount1 = first.count { it is ConvergenceEffect.FenceGeneration }
        val second = gbc.acceptVerifiedFact(g2)
        val fenceCount2 = second.count { it is ConvergenceEffect.FenceGeneration }
        assertEquals(1, fenceCount1)
        assertEquals(0, fenceCount2)
        assertEquals(ObligationState.CLOSED, gbc.snapshot().obligation)
    }

    @Test
    fun cR2_forbiddenProxies_documented() {
        assertTrue(GbcLegacyAuthorityProxies.ALL.contains("waitingForPrimary"))
        assertTrue(GbcLegacyAuthorityProxies.ALL.contains("bootstrapAttemptCount"))
        assertFalse(GbcLegacyAuthorityProxies.isForbiddenAuthorityInput("AuthoritativeGenerationFact"))
    }

    private fun currentFact(
        id: String,
        predecessor: String?,
    ): AuthoritativeGenerationFact =
        AuthoritativeGenerationFact(
            generationIdentity = id,
            predecessorGenerationIdentity = predecessor,
            originAuthorityIdentity = "M01",
            attestsCurrent = true,
            semanticDigest = "digest-$id",
        )
}
