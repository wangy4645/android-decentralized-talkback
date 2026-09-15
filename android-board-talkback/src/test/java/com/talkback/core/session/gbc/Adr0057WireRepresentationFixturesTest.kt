package com.talkback.core.session.gbc

import com.talkback.core.model.GenerationFactRequestPayload
import com.talkback.core.model.GenerationFactResponseCandidatePayload
import com.talkback.core.model.GenerationFactResponseInsufficientPayload
import com.talkback.core.model.PredecessorWire
import com.talkback.core.model.SignalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0057 Wire Representation gates W1–W7 (narrow auth).
 * Injectable verifier ≠ production crypto evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Adr0057WireRepresentationFixturesTest {
    @Test
    fun w1_distinctFactDiscriminants_notOverloadingLegacy() {
        assertTrue(SignalType.entries.contains(SignalType.FACT_REQUEST))
        assertTrue(SignalType.entries.contains(SignalType.FACT_RESPONSE_CANDIDATE))
        assertTrue(SignalType.entries.contains(SignalType.FACT_RESPONSE_INSUFFICIENT))
        assertFalse(SignalType.HELLO.name.startsWith("FACT_"))
        assertFalse(SignalType.GROUP_INVITE.name.startsWith("FACT_"))
        assertFalse(SignalType.ENDPOINT_TEXT.name.startsWith("FACT_"))
    }

    @Test
    fun w2_semanticRoundTrip_preservesS2() {
        val original =
            GenerationFactResponseCandidatePayload(
                channelId = "CH",
                correlationId = "c1",
                requesterModuleId = "M02",
                responderModuleId = "M01",
                generationIdentity = "G2",
                predecessor = PredecessorWire.Id("G1"),
                originAuthorityIdentity = "M01",
                attestsCurrent = true,
                semanticDigest = "digest-g2",
                resolvesConflictSet = setOf("Gx"),
                verificationMaterial = "vm-bytes-1",
            )
        val decoded = GenerationFactResponseCandidatePayload.decode(original.encode())
        assertNotNull(decoded)
        assertEquals(original, decoded)
        val candidate = GenerationFactWireCodec.toCandidate(decoded!!)
        assertEquals("G2", candidate.claimedGenerationIdentity)
        assertEquals("G1", candidate.claimedPredecessorGenerationIdentity)
        assertEquals("M01", candidate.claimedOriginAuthorityIdentity)
        assertTrue(candidate.claimedAttestsCurrent)
        assertEquals("digest-g2", candidate.claimedSemanticDigest)
        assertEquals("vm-bytes-1", candidate.opaqueMaterial)
        // Codec produces candidate only (not AuthoritativeGenerationFact).
        assertEquals("digest-g2", candidate.claimedFactIdentity)
    }

    @Test
    fun w2_predecessorAbsent_vsNone_roundTrip() {
        val absent =
            GenerationFactResponseCandidatePayload(
                channelId = "CH",
                correlationId = "c",
                requesterModuleId = "M02",
                responderModuleId = "M01",
                generationIdentity = "G*",
                predecessor = PredecessorWire.Absent,
                originAuthorityIdentity = "M01",
                attestsCurrent = true,
                semanticDigest = "d*",
                verificationMaterial = "",
            )
        assertEquals(PredecessorWire.Absent, GenerationFactResponseCandidatePayload.decode(absent.encode())!!.predecessor)

        val none = absent.copy(predecessor = PredecessorWire.None, semanticDigest = "d0", generationIdentity = "G0")
        assertEquals(PredecessorWire.None, GenerationFactResponseCandidatePayload.decode(none.encode())!!.predecessor)
    }

    @Test
    fun w2_generationIdentityOnly_rejectedByStructuralDecode() {
        // Missing attestation / origin / verificationMaterial slot → decode null
        val raw =
            """{"channelId":"CH","correlationId":"c","requesterModuleId":"M02",
                |"responderModuleId":"M01","generationIdentity":"G1","semanticDigest":"d1"}"""
                .trimMargin()
        assertNull(GenerationFactResponseCandidatePayload.decode(raw))
    }

    @Test
    fun w3_deliveryContext_notInCandidateAuthorityFields() {
        val payload =
            GenerationFactResponseCandidatePayload(
                channelId = "CH",
                correlationId = "corr",
                requesterModuleId = "M02",
                responderModuleId = "M99",
                generationIdentity = "G1",
                predecessor = PredecessorWire.None,
                originAuthorityIdentity = "M01",
                attestsCurrent = true,
                semanticDigest = "d1",
                verificationMaterial = "vm",
            )
        val candidate = GenerationFactWireCodec.toCandidate(payload)
        // responder ≠ origin
        assertEquals("M01", candidate.claimedOriginAuthorityIdentity)
        assertFalse(candidate.claimedOriginAuthorityIdentity == payload.responderModuleId)
    }

    @Test
    fun w4_verificationMaterial_lossless_doesNotImplySuccess() {
        val payload =
            GenerationFactResponseCandidatePayload(
                channelId = "CH",
                correlationId = "c",
                requesterModuleId = "M02",
                responderModuleId = "M01",
                generationIdentity = "G1",
                predecessor = PredecessorWire.None,
                originAuthorityIdentity = "M01",
                attestsCurrent = true,
                semanticDigest = "d1",
                verificationMaterial = "opaque-XYZ",
            )
        val round = GenerationFactResponseCandidatePayload.decode(payload.encode())!!
        assertEquals("opaque-XYZ", round.verificationMaterial)

        val verifier = InjectableGenerationFactVerifier()
        val boundary = VerificationBoundary(verifier)
        verifier.stubOpaque("opaque-XYZ", VerificationOutcome.VerifyFail)
        val outcome = boundary.admit(GenerationFactWireCodec.toCandidate(round))
        assertEquals(VerificationOutcome.VerifyFail, outcome)
        assertFalse(outcome.isPromotable())
    }

    @Test
    fun w5_inboundBoundary_noCodecBypassToGbc() {
        val verifier = InjectableGenerationFactVerifier()
        val wiring = GroupBootstrapConvergenceWiring()
        val orch = FactDeliveryOrchestrator(wiring, VerificationBoundary(verifier))
        val payload =
            GenerationFactResponseCandidatePayload(
                channelId = "CH",
                correlationId = "c",
                requesterModuleId = "M02",
                responderModuleId = "M01",
                generationIdentity = "G*",
                predecessor = PredecessorWire.None,
                originAuthorityIdentity = "M01",
                attestsCurrent = true,
                semanticDigest = "d*",
                verificationMaterial = "vm",
            )
        // Decode alone does not enter GBC
        val candidate = GenerationFactWireCodec.toCandidate(payload)
        assertNull(wiring.snapshot("CH").acceptedCurrent)

        verifier.stubOpaque("vm", VerificationOutcome.VerifyFail)
        orch.onCandidateResponse("CH", "c", candidate, admissiblePath = true)
        assertNull(wiring.snapshot("CH").acceptedCurrent)

        verifier.stubOpaque("vm", successFromCandidate(candidate))
        orch.onCandidateResponse("CH", "c", candidate, admissiblePath = true)
        assertEquals("G*", wiring.snapshot("CH").acceptedCurrent!!.generationIdentity)
    }

    @Test
    fun w6_reprovide_buildsCandidateResponse_remoteMustVerify() {
        val holderVerifier = InjectableGenerationFactVerifier()
        val holderWiring = GroupBootstrapConvergenceWiring()
        val holderOrch = FactDeliveryOrchestrator(holderWiring, VerificationBoundary(holderVerifier))
        val good =
            GenerationFactCandidate(
                claimedFactIdentity = "d*",
                claimedGenerationIdentity = "G*",
                claimedPredecessorGenerationIdentity = null,
                claimedOriginAuthorityIdentity = "M01",
                claimedAttestsCurrent = true,
                claimedSemanticDigest = "d*",
                opaqueMaterial = "vm-hold",
            )
        holderVerifier.stubOpaque("vm-hold", successFromCandidate(good))
        holderOrch.onCandidateResponse("CH", "c1", good, admissiblePath = true)

        val reprovide = holderOrch.onAuthorizedReprovideRequest("CH") as ReprovideResult.Fact
        val wire =
            GenerationFactWireCodec.fromAcceptedFact(
                channelId = "CH",
                correlationId = "c2",
                requesterModuleId = "M02",
                responderModuleId = "M01",
                fact = reprovide.fact,
                verificationMaterial = reprovide.verificationMaterial,
            )
        assertEquals("vm-hold", wire.verificationMaterial)

        val recvVerifier = InjectableGenerationFactVerifier()
        val recvWiring = GroupBootstrapConvergenceWiring()
        val recvOrch = FactDeliveryOrchestrator(recvWiring, VerificationBoundary(recvVerifier))
        recvVerifier.stubOpaque("vm-hold", VerificationOutcome.VerifyFail)
        recvOrch.onCandidateResponse(
            "CH",
            "c2",
            GenerationFactWireCodec.toCandidate(wire),
            admissiblePath = true,
        )
        assertNull(recvWiring.snapshot("CH").acceptedCurrent)
    }

    @Test
    fun requestAndInsufficient_roundTrip() {
        val req =
            GenerationFactRequestPayload(
                channelId = "CH",
                correlationId = "c",
                requesterModuleId = "M02",
                targetHolderModuleId = "M01",
            )
        assertEquals(req, GenerationFactRequestPayload.decode(req.encode()))
        val insuf =
            GenerationFactResponseInsufficientPayload(
                channelId = "CH",
                correlationId = "c",
                requesterModuleId = "M02",
                responderModuleId = "M01",
            )
        assertEquals(insuf, GenerationFactResponseInsufficientPayload.decode(insuf.encode()))
    }
}
