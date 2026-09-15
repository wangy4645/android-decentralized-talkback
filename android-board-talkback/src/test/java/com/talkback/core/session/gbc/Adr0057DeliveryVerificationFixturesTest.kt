package com.talkback.core.session.gbc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0057 Delivery + Verification V1–V3 harness (narrow auth).
 * Injectable verifier may only emit Verification Boundary outcomes — never F1/obligation.
 */
class Adr0057DeliveryVerificationFixturesTest {
    @Test
    fun v1_verifyFail_doesNotEraseAcceptedCurrent() {
        val verifier = InjectableGenerationFactVerifier()
        val boundary = VerificationBoundary(verifier)
        val wiring = GroupBootstrapConvergenceWiring()
        val orch = FactDeliveryOrchestrator(wiring, boundary)

        val good = candidate("G*", digest = "digest-gstar", opaque = "good")
        verifier.stubOpaque("good", successFromCandidate(good))
        val accepted = orch.onCandidateResponse("CH", "c1", good, admissiblePath = true)
        assertTrue(accepted is FactDeliveryResult.VerifiedAccepted)
        assertEquals(ObligationState.CLOSED, wiring.snapshot("CH").obligation)
        assertEquals("G*", wiring.snapshot("CH").acceptedCurrent!!.generationIdentity)

        val bad = candidate("G-bad", digest = "digest-bad", opaque = "bad")
        verifier.stubOpaque("bad", VerificationOutcome.VerifyFail)
        val failed = orch.onCandidateResponse("CH", "c2", bad, admissiblePath = true)
        assertTrue(failed is FactDeliveryResult.NotPromoted)
        assertEquals(VerificationOutcome.VerifyFail, (failed as FactDeliveryResult.NotPromoted).outcome)

        // Non-regression (V1.3)
        assertEquals("G*", wiring.snapshot("CH").acceptedCurrent!!.generationIdentity)
        assertEquals(ObligationState.CLOSED, wiring.snapshot("CH").obligation)
        assertTrue(wiring.snapshot("CH").knowledge is LocalGenerationKnowledge.Known)
    }

    @Test
    fun v1_unresolved_failure_staysUnknown_noInventedCurrent() {
        val verifier = InjectableGenerationFactVerifier()
        val boundary = VerificationBoundary(verifier)
        val wiring = GroupBootstrapConvergenceWiring()
        val orch = FactDeliveryOrchestrator(wiring, boundary)
        wiring.observeAcquisitionIfUnresolved("CH-U", "cold")

        val bad = candidate("Gx", digest = "digest-x", opaque = "fail")
        verifier.stubOpaque("fail", VerificationOutcome.Unverifiable)
        orch.onCandidateResponse("CH-U", "c", bad, admissiblePath = true)

        assertEquals(null, wiring.snapshot("CH-U").acceptedCurrent)
        assertTrue(wiring.snapshot("CH-U").knowledge is LocalGenerationKnowledge.Unknown)
        assertEquals(ObligationState.OPEN, wiring.snapshot("CH-U").obligation)
    }

    @Test
    fun v1_integrityAnomaly_mismatchedIdentityDigest_notPromoted() {
        val boundary = VerificationBoundary(InjectableGenerationFactVerifier())
        val candidate =
            GenerationFactCandidate(
                claimedFactIdentity = "id-A",
                claimedGenerationIdentity = "G1",
                claimedPredecessorGenerationIdentity = null,
                claimedOriginAuthorityIdentity = "M01",
                claimedAttestsCurrent = true,
                claimedSemanticDigest = "digest-B",
                opaqueMaterial = "anom",
            )
        assertEquals(VerificationOutcome.IntegrityAnomaly, boundary.admit(candidate))
    }

    @Test
    fun v2_helloLocate_andSelection_doNotManufactureCurrent() {
        val wiring = GroupBootstrapConvergenceWiring()
        val orch = FactDeliveryOrchestrator(wiring, VerificationBoundary(InjectableGenerationFactVerifier()))
        orch.onHelloLocate("CH", "M02")
        orch.onHelloLocate("CH", "M03")
        val schedule = orch.scheduleAcquisition("CH", "corr-1")
        assertEquals(listOf("M02", "M03"), schedule.selectedHolders)
        assertEquals(null, wiring.snapshot("CH").acceptedCurrent)
        assertTrue(wiring.snapshot("CH").obligation == ObligationState.OPEN)
    }

    @Test
    fun v2_insufficient_isNotProgress_nonRegression() {
        val verifier = InjectableGenerationFactVerifier()
        val wiring = GroupBootstrapConvergenceWiring()
        val orch = FactDeliveryOrchestrator(wiring, VerificationBoundary(verifier))
        val good = candidate("G*", digest = "d*", opaque = "ok")
        verifier.stubOpaque("ok", successFromCandidate(good))
        orch.onCandidateResponse("CH", "c1", good, admissiblePath = true)
        assertEquals(ObligationState.CLOSED, wiring.snapshot("CH").obligation)

        orch.onInsufficientResponse("CH", "late", admissiblePath = true)
        assertEquals(ObligationState.CLOSED, wiring.snapshot("CH").obligation)
        assertEquals("G*", wiring.snapshot("CH").acceptedCurrent!!.generationIdentity)
    }

    @Test
    fun v3_reprovide_onlyAcceptedCurrent_receiverMustVerify() {
        val holderVerifier = InjectableGenerationFactVerifier()
        val holderWiring = GroupBootstrapConvergenceWiring()
        val holderOrch = FactDeliveryOrchestrator(holderWiring, VerificationBoundary(holderVerifier))
        val good = candidate("G*", digest = "d*", opaque = "ok")
        holderVerifier.stubOpaque("ok", successFromCandidate(good))
        holderOrch.onCandidateResponse("CH", "c1", good, admissiblePath = true)

        val reprovide = holderOrch.onAuthorizedReprovideRequest("CH")
        assertTrue(reprovide is ReprovideResult.Fact)
        val fact = (reprovide as ReprovideResult.Fact).fact

        // Receiver path: even holder-verified Fact is candidate until local VB (V3.4)
        val recvVerifier = InjectableGenerationFactVerifier()
        val recvWiring = GroupBootstrapConvergenceWiring()
        val recvOrch = FactDeliveryOrchestrator(recvWiring, VerificationBoundary(recvVerifier))
        val asCandidate =
            GenerationFactCandidate(
                claimedFactIdentity = fact.factIdentity,
                claimedGenerationIdentity = fact.generationIdentity,
                claimedPredecessorGenerationIdentity = fact.predecessorGenerationIdentity,
                claimedOriginAuthorityIdentity = fact.originAuthorityIdentity,
                claimedAttestsCurrent = fact.attestsCurrent,
                claimedSemanticDigest = fact.semanticDigest,
                opaqueMaterial = "fwd",
            )
        // Without local SUCCESS stub → not promoted
        recvVerifier.stubOpaque("fwd", VerificationOutcome.VerifyFail)
        val denied = recvOrch.onCandidateResponse("CH", "r1", asCandidate, admissiblePath = true)
        assertTrue(denied is FactDeliveryResult.NotPromoted)
        assertEquals(null, recvWiring.snapshot("CH").acceptedCurrent)

        recvVerifier.stubOpaque("fwd", successFromCandidate(asCandidate))
        val accepted = recvOrch.onCandidateResponse("CH", "r2", asCandidate, admissiblePath = true)
        assertTrue(accepted is FactDeliveryResult.VerifiedAccepted)
        assertEquals("G*", recvWiring.snapshot("CH").acceptedCurrent!!.generationIdentity)
    }

    @Test
    fun v3_reprovideWithoutCurrent_returnsInsufficient() {
        val orch =
            FactDeliveryOrchestrator(
                GroupBootstrapConvergenceWiring(),
                VerificationBoundary(InjectableGenerationFactVerifier()),
            )
        assertEquals(ReprovideResult.Insufficient, orch.onAuthorizedReprovideRequest("CH-EMPTY"))
    }

    @Test
    fun verifierDouble_mustNotEmitF1OrObligation() {
        // Compile-time / API surface: VerificationOutcome has no SAME/SUPERSEDED/CLOSED.
        val outcomes =
            listOf(
                VerificationOutcome.Malformed,
                VerificationOutcome.Unverifiable,
                VerificationOutcome.VerifyFail,
                VerificationOutcome.IntegrityAnomaly,
            )
        outcomes.forEach { assertFalse(it.isPromotable()) }
    }

    private fun candidate(
        generation: String,
        digest: String,
        opaque: String,
        predecessor: String? = null,
    ): GenerationFactCandidate =
        GenerationFactCandidate(
            claimedFactIdentity = digest,
            claimedGenerationIdentity = generation,
            claimedPredecessorGenerationIdentity = predecessor,
            claimedOriginAuthorityIdentity = "M01",
            claimedAttestsCurrent = true,
            claimedSemanticDigest = digest,
            opaqueMaterial = opaque,
        )
}
