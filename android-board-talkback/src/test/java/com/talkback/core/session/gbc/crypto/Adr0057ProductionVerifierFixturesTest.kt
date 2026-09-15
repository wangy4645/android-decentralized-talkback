package com.talkback.core.session.gbc.crypto

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.talkback.core.model.PredecessorWire
import com.talkback.core.session.gbc.FactDeliveryOrchestrator
import com.talkback.core.session.gbc.GroupBootstrapConvergenceWiring
import com.talkback.core.session.gbc.GenerationFactWireCodec
import com.talkback.core.session.gbc.ReprovideResult
import com.talkback.core.session.gbc.VerificationBoundary
import com.talkback.core.session.gbc.VerificationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0057 PV-1 Production Verifier Core conformance harness.
 * Uses fixture trust only — not production authority wiring.
 * Plain JUnit (no Robolectric) — module test task uses forkEvery=1 to avoid Security provider pollution.
 */
class Adr0057ProductionVerifierFixturesTest {
    private val gson = Gson()

    @Test
    fun pv1_g4_activeVerification_success() {
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority("G-ACTIVE")
        val candidate =
            GenerationFactTestSigning.buildCandidate(
                authority = authority,
                signerKeyVersion = GenerationFactPv1FixtureSupport.KEY_VERSION,
                trustBindingRevision = GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        val verifier = ProductionGenerationFactVerifier(GenerationFactPv1FixtureSupport.activeTrustLookup())
        val outcome = VerificationBoundary(verifier).admit(candidate)
        assertTrue(outcome is VerificationOutcome.Success)
        assertEquals("G-ACTIVE", (outcome as VerificationOutcome.Success).fact.generationIdentity)
    }

    @Test
    fun pv1_g5_retiredVerify_withInclusion_success() {
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority("G-HIST")
        val envelope =
            GenerationFactTestSigning.signAuthority(
                authority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        val commitment =
            GenerationFactCanonicalCodec.computeSemanticDigest(envelope.authorityCanonicalBytes)
        val proof =
            GenerationFactInclusionProofVerifier.buildProof(listOf(commitment), commitment)
                ?: error("proof required")
        val candidate =
            GenerationFactTestSigning.buildCandidate(
                authority = authority,
                signerKeyVersion = GenerationFactPv1FixtureSupport.KEY_VERSION,
                trustBindingRevision = GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
                inclusionProof = proof,
            )
        val lookup = GenerationFactPv1FixtureSupport.retiredTrustLookup(listOf(commitment))
        val outcome = VerificationBoundary(ProductionGenerationFactVerifier(lookup)).admit(candidate)
        assertTrue(outcome is VerificationOutcome.Success)
    }

    @Test
    fun pv1_n2_postRetirementForgery_validSignature_noInclusion_verifyFail() {
        val issued = GenerationFactPv1FixtureSupport.genesisAuthority("G-ISSUED")
        val issuedEnvelope =
            GenerationFactTestSigning.signAuthority(
                issued,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        val issuedCommitment =
            GenerationFactCanonicalCodec.computeSemanticDigest(issuedEnvelope.authorityCanonicalBytes)
        val lookup = GenerationFactPv1FixtureSupport.retiredTrustLookup(listOf(issuedCommitment))

        val forged = GenerationFactPv1FixtureSupport.genesisAuthority("G999")
        val forgedCandidate =
            GenerationFactTestSigning.buildCandidate(
                authority = forged,
                signerKeyVersion = GenerationFactPv1FixtureSupport.KEY_VERSION,
                trustBindingRevision = GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        val outcome =
            VerificationBoundary(ProductionGenerationFactVerifier(lookup)).admit(forgedCandidate)
        assertEquals(VerificationOutcome.VerifyFail, outcome)
    }

    @Test
    fun pv1_n1_keySelectorSubstitution_tamperedContext_verifyFail() {
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority("G-N1")
        val envelope =
            GenerationFactTestSigning.signAuthority(
                authority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        val tamperedContext =
            GenerationFactCanonicalCodec.encodeVerificationContext(
                GenerationFactCanonicalCodec.VerificationContext(
                    originAuthorityIdentity = authority.originAuthorityIdentity,
                    signerKeyVersion = GenerationFactPv1FixtureSupport.KEY_VERSION,
                    trustBindingRevision = GenerationFactPv1FixtureSupport.ACTIVE_REVISION + 999,
                ),
            )
        val tampered =
            SignedGenerationFactEnvelope(
                authorityCanonicalBytes = envelope.authorityCanonicalBytes,
                verificationContextBytes = tamperedContext,
                signatureRs = envelope.signatureRs,
            )
        val digestHex = GenerationFactCanonicalCodec.semanticDigestHex(envelope.authorityCanonicalBytes)
        val bundle =
            GenerationFactVerificationBundle(signedFactBytes = tampered.toSignedFactBytes())
        val candidate =
            GenerationFactTestSigning.buildCandidate(
                authority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            ).copy(opaqueMaterial = bundle.encodeToVerificationMaterial(), claimedSemanticDigest = digestHex)
        val outcome =
            VerificationBoundary(
                ProductionGenerationFactVerifier(GenerationFactPv1FixtureSupport.activeTrustLookup()),
            ).admit(candidate)
        assertEquals(VerificationOutcome.VerifyFail, outcome)
    }

    @Test
    fun pv1_g7_v12Taxonomy_samples() {
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority("G-TAX")
        val candidate =
            GenerationFactTestSigning.buildCandidate(
                authority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        assertEquals(
            VerificationOutcome.Malformed,
            VerificationBoundary(ProductionGenerationFactVerifier(GenerationFactPv1FixtureSupport.activeTrustLookup()))
                .admit(candidate.copy(opaqueMaterial = "not-json")),
        )
        assertEquals(
            VerificationOutcome.Unverifiable,
            VerificationBoundary(ProductionGenerationFactVerifier(FixtureEmptyTrustLookup()))
                .admit(candidate),
        )
        assertEquals(
            VerificationOutcome.IntegrityAnomaly,
            VerificationBoundary(ProductionGenerationFactVerifier(GenerationFactPv1FixtureSupport.activeTrustLookup()))
                .admit(candidate.copy(claimedSemanticDigest = "0".repeat(64))),
        )
    }

    @Test
    fun pv1_g9_relayPreservesExactSignedInnerObject() {
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority("G-RELAY")
        val candidate =
            GenerationFactTestSigning.buildCandidate(
                authority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        val bundle =
            GenerationFactVerificationBundle.decodeFromVerificationMaterial(candidate.opaqueMaterial)!!
        val originalSigned = bundle.signedFactBytes.copyOf()

        val holderVerifier = ProductionGenerationFactVerifier(GenerationFactPv1FixtureSupport.activeTrustLookup())
        val holderWiring = GroupBootstrapConvergenceWiring()
        val holderOrch = FactDeliveryOrchestrator(holderWiring, VerificationBoundary(holderVerifier))
        val holderResult = holderOrch.onCandidateResponse("CH", "c1", candidate, admissiblePath = true)
        assertTrue(holderResult is com.talkback.core.session.gbc.FactDeliveryResult.VerifiedAccepted)

        val reprovide = holderOrch.onAuthorizedReprovideRequest("CH")
        assertTrue(reprovide is ReprovideResult.Fact)
        val wire =
            GenerationFactWireCodec.fromAcceptedFact(
                channelId = "CH",
                correlationId = "c2",
                requesterModuleId = "M02",
                responderModuleId = "M01",
                fact = (reprovide as ReprovideResult.Fact).fact,
                verificationMaterial = reprovide.verificationMaterial,
            )
        val relayBundle =
            GenerationFactVerificationBundle.decodeFromVerificationMaterial(wire.verificationMaterial)!!
        assertTrue(relayBundle.signedFactBytes.contentEquals(originalSigned))

        val recvOutcome =
            VerificationBoundary(
                ProductionGenerationFactVerifier(GenerationFactPv1FixtureSupport.activeTrustLookup()),
            ).admit(GenerationFactWireCodec.toCandidate(wire))
        assertTrue(recvOutcome is VerificationOutcome.Success)
    }

    @Test
    fun pv1_g2_c1_primitiveChecks() {
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority("G-CANON")
        val envelope =
            GenerationFactTestSigning.signAuthority(
                authority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        val digest = GenerationFactCanonicalCodec.computeSemanticDigest(envelope.authorityCanonicalBytes)
        val digestHex = GenerationFactCanonicalCodec.semanticDigestHex(envelope.authorityCanonicalBytes)
        assertEquals(32, digest.size)
        assertEquals(64, digestHex.length)
        assertTrue(EcdsaP256Verifier.validateSignatureForm(envelope.signatureRs))
        val invalidLength = ByteArray(63)
        assertFalse(EcdsaP256Verifier.validateSignatureForm(invalidLength))
    }

    @Test
    fun pv1_g11_goldenVectors_selfCheck() {
        Pv1GoldenVectorGenerator.cases().forEach { golden ->
            val outcome =
                VerificationBoundary(ProductionGenerationFactVerifier(golden.lookup)).admit(golden.candidate)
            assertEquals(
                "vector ${golden.name} outcome",
                golden.expectedOutcome,
                outcome.javaClass.simpleName,
            )
        }
    }

    @Test
    fun pv1_g11b_goldenResourceDigestRegistryPresent() {
        val raw =
            javaClass.classLoader
                ?.getResourceAsStream("adr0057-pv1-golden-vectors.json")
                ?.bufferedReader()
                ?.readText()
                ?: error("golden vectors missing")
        val root = gson.fromJson(raw, JsonObject::class.java)
        assertEquals("adr0057-pv1-golden-vectors-v1", root.get("schema").asString)
        assertTrue(root.getAsJsonArray("vectors").size() >= 3)
    }
}

private class FixtureEmptyTrustLookup : com.talkback.core.session.gbc.trust.GenerationFactTrustLookup {
    override fun lookupBinding(
        moduleId: String,
        signerKeyVersion: Long,
        trustBindingRevision: Long,
    ) = com.talkback.core.session.gbc.trust.GenerationFactTrustLookupResult.UnknownModule

    override fun retirementCheckpoint(
        moduleId: String,
        signerKeyVersion: Long,
    ) = null
}
