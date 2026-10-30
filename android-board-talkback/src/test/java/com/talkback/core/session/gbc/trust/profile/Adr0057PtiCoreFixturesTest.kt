package com.talkback.core.session.gbc.trust.profile

import com.talkback.core.model.PredecessorWire
import com.talkback.core.session.gbc.VerificationBoundary
import com.talkback.core.session.gbc.VerificationOutcome
import com.talkback.core.session.gbc.crypto.GenerationFactCanonicalCodec
import com.talkback.core.session.gbc.crypto.GenerationFactInclusionProofVerifier
import com.talkback.core.session.gbc.crypto.GenerationFactPv1FixtureSupport
import com.talkback.core.session.gbc.crypto.GenerationFactTestSigning
import com.talkback.core.session.gbc.crypto.GenerationFactVerificationBundle
import com.talkback.core.session.gbc.crypto.ProductionGenerationFactVerifier
import com.talkback.core.session.gbc.trust.GenerationFactTrustLookupResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

/**
 * ADR-0057 PTI Layer A conformance harness (PTI-G1-A..G14).
 * Fixture authentication only — not production Profile authority.
 */
class Adr0057PtiCoreFixturesTest {
    @Test
    fun pti_g1a_authenticationGatedAcceptance_rejectsUnauthenticated() {
        val h = GenerationFactPtiFixtureSupport.harness()
        val payload = GenerationFactPtiFixtureSupport.activeBindingPayload(revision = 10)
        val result = h.acceptor.acceptPayload(payload)
        assertTrue(result is ProfileRevisionAcceptResult.AuthenticationRejected)
        assertEquals(null, h.store.currentSnapshot())
    }

    @Test
    fun pti_g1a_authenticationGatedAcceptance_acceptsRegisteredPayload() {
        val h = GenerationFactPtiFixtureSupport.harness()
        val payload = GenerationFactPtiFixtureSupport.activeBindingPayload(revision = 10)
        val result = GenerationFactPtiFixtureSupport.accept(h, payload)
        assertTrue(result is ProfileRevisionAcceptResult.Accepted)
        assertNotNull(h.store.currentSnapshot())
    }

    @Test
    fun pti_g2_rollbackRejected_preservesLastGood() {
        val h = GenerationFactPtiFixtureSupport.harness()
        val first = GenerationFactPtiFixtureSupport.activeBindingPayload(revision = 20)
        val firstResult = GenerationFactPtiFixtureSupport.accept(h, first)
        assertTrue(firstResult is ProfileRevisionAcceptResult.Accepted)
        val rollback = GenerationFactPtiFixtureSupport.activeBindingPayload(revision = 10)
        h.authenticator.allowPayload(rollback)
        val result = h.acceptor.acceptPayload(rollback)
        assertTrue(result is ProfileRevisionAcceptResult.RollbackRejected)
        assertEquals(20L, h.store.currentSnapshot()?.taskProfileRevision)
    }

    @Test
    fun pti_g2_sameRevisionIdentityConflict_rejectsIncoming() {
        val h = GenerationFactPtiFixtureSupport.harness()
        val first = GenerationFactPtiFixtureSupport.activeBindingPayload(revision = 10)
        GenerationFactPtiFixtureSupport.accept(h, first)
        val conflicting =
            GenerationFactPtiFixtureSupport.activeBindingPayload(revision = 10).copy(
                localModuleId = "OTHER",
            )
        GenerationFactPtiFixtureSupport.accept(h, conflicting)
        val result = h.acceptor.acceptPayload(conflicting)
        assertTrue(result is ProfileRevisionAcceptResult.RevisionConflictRejected)
        assertEquals(GenerationFactPtiFixtureSupport.MODULE, h.store.currentSnapshot()?.localModuleId)
    }

    @Test
    fun pti_ia_t4_revisionIdentityDerivedFromProtectedPayload() {
        val payload = GenerationFactPtiFixtureSupport.activeBindingPayload(revision = 11)
        val bytes = ProfileRevisionCanonicalCodec.encode(payload)
        val id1 = ProfileRevisionIdentity.fromProtectedBytes(bytes)
        val id2 = ProfileRevisionIdentity.fromProtectedBytes(bytes)
        assertArrayEquals(id1, id2)

        val tampered = bytes.copyOf()
        tampered[tampered.size - 1] = (tampered.last().toInt() xor 0x01).toByte()
        val id3 = ProfileRevisionIdentity.fromProtectedBytes(tampered)
        assertFalse(id1.contentEquals(id3))
    }

    @Test
    fun pti_g3_stableBindingRebindRejected() {
        val h = GenerationFactPtiFixtureSupport.harness()
        GenerationFactPtiFixtureSupport.accept(h, GenerationFactPtiFixtureSupport.activeBindingPayload(revision = 10))
        val rebind =
            GenerationFactPtiFixtureSupport.activeBindingPayload(revision = 11).copy(
                moduleBindings =
                    listOf(
                        GenerationFactPtiFixtureSupport.activeBindingPayload().moduleBindings.single().copy(
                            publicKeySpki = ByteArray(32) { 0x42 },
                        ),
                    ),
            )
        GenerationFactPtiFixtureSupport.accept(h, rebind)
        val result = h.acceptor.acceptPayload(rebind)
        assertTrue(result is ProfileRevisionAcceptResult.SemanticRejected)
    }

    @Test
    fun pti_g4_trustBindingRevisionEnforced() {
        val h = GenerationFactPtiFixtureSupport.harness()
        GenerationFactPtiFixtureSupport.accept(h, GenerationFactPtiFixtureSupport.activeBindingPayload())
        val lookup = h.lookup.lookupBinding(
            GenerationFactPtiFixtureSupport.MODULE,
            GenerationFactPtiFixtureSupport.KEY_VERSION,
            GenerationFactPtiFixtureSupport.ACTIVE_REVISION - 1,
        )
        assertTrue(lookup is GenerationFactTrustLookupResult.InvalidTrustBindingRevision)
    }

    @Test
    fun pti_g5_preparedNotProjectedAsVerifiable() {
        val h = GenerationFactPtiFixtureSupport.harness()
        GenerationFactPtiFixtureSupport.accept(h, GenerationFactPtiFixtureSupport.preparedBindingPayload())
        val lookup =
            h.lookup.lookupBinding(
                GenerationFactPtiFixtureSupport.MODULE,
                GenerationFactPtiFixtureSupport.KEY_VERSION,
                GenerationFactPtiFixtureSupport.ACTIVE_REVISION,
            )
        assertTrue(lookup is GenerationFactTrustLookupResult.UnknownKeyVersion)
    }

    @Test
    fun pti_g6_retiredVerifyWithoutCheckpointRejected() {
        val h = GenerationFactPtiFixtureSupport.harness()
        val bad =
            GenerationFactProfileTrustPayload(
                taskProfileRevision = 20,
                localModuleId = GenerationFactPtiFixtureSupport.MODULE,
                moduleBindings =
                    listOf(
                        ModuleSigningBinding(
                            moduleId = GenerationFactPtiFixtureSupport.MODULE,
                            signerKeyVersion = GenerationFactPtiFixtureSupport.KEY_VERSION,
                            keyState = com.talkback.core.session.gbc.trust.GenerationFactKeyState.RETIRED_VERIFY,
                            publicKeySpki = GenerationFactTestSigning.publicKeySpki,
                            activatedAtRevision = GenerationFactPtiFixtureSupport.ACTIVE_REVISION,
                            retiredVerifyAtRevision = GenerationFactPtiFixtureSupport.RETIRED_REVISION,
                            retirementCheckpoint = null,
                        ),
                    ),
            )
        GenerationFactPtiFixtureSupport.accept(h, bad)
        val result = h.acceptor.acceptPayload(bad)
        assertTrue(result is ProfileRevisionAcceptResult.SemanticRejected)
        assertEquals(null, h.store.currentSnapshot())
    }

    @Test
    fun pti_g7_checkpointFromAcceptedStateOnly() {
        val h = GenerationFactPtiFixtureSupport.harness()
        val commitment = ByteArray(32) { 0x11 }
        val root = GenerationFactInclusionProofVerifier.merkleRoot(listOf(commitment))
        GenerationFactPtiFixtureSupport.accept(
            h,
            GenerationFactPtiFixtureSupport.retiredBindingPayload(root),
        )
        val checkpoint = h.lookup.retirementCheckpoint(
            GenerationFactPtiFixtureSupport.MODULE,
            GenerationFactPtiFixtureSupport.KEY_VERSION,
        )
        assertNotNull(checkpoint)
        assertArrayEquals(root, checkpoint!!.issuanceRoot)
    }

    @Test
    fun pti_g10_rejectPreservesLastGood_onSemanticFailure() {
        val h = GenerationFactPtiFixtureSupport.harness()
        GenerationFactPtiFixtureSupport.accept(h, GenerationFactPtiFixtureSupport.activeBindingPayload(revision = 10))
        val bad =
            GenerationFactProfileTrustPayload(
                taskProfileRevision = 11,
                localModuleId = GenerationFactPtiFixtureSupport.MODULE,
                moduleBindings =
                    listOf(
                        ModuleSigningBinding(
                            moduleId = GenerationFactPtiFixtureSupport.MODULE,
                            signerKeyVersion = GenerationFactPtiFixtureSupport.KEY_VERSION,
                            keyState = com.talkback.core.session.gbc.trust.GenerationFactKeyState.RETIRED_VERIFY,
                            publicKeySpki = GenerationFactTestSigning.publicKeySpki,
                            activatedAtRevision = GenerationFactPtiFixtureSupport.ACTIVE_REVISION,
                            retiredVerifyAtRevision = GenerationFactPtiFixtureSupport.RETIRED_REVISION,
                            retirementCheckpoint = null,
                        ),
                    ),
            )
        h.authenticator.allowPayload(bad)
        val result = h.acceptor.acceptPayload(bad)
        assertTrue(result is ProfileRevisionAcceptResult.SemanticRejected)
        assertEquals(10L, h.store.currentSnapshot()?.taskProfileRevision)
    }

    @Test
    fun pti_g11_emptyStoreEstablishesNoFactTrust() {
        val lookup = ProfileBackedGenerationFactTrustLookup(AcceptedLocalTrustStateStore())
        val result =
            lookup.lookupBinding(
                GenerationFactPtiFixtureSupport.MODULE,
                GenerationFactPtiFixtureSupport.KEY_VERSION,
                GenerationFactPtiFixtureSupport.ACTIVE_REVISION,
            )
        assertTrue(result is GenerationFactTrustLookupResult.UnknownModule)
    }

    @Test
    fun pti_g12a_pv1VerifierWithProfileBackedLookup_activeSuccess() {
        val h = GenerationFactPtiFixtureSupport.harness()
        GenerationFactPtiFixtureSupport.accept(h, GenerationFactPtiFixtureSupport.activeBindingPayload())
        val authority =
            GenerationFactCanonicalCodec.AuthoritySemantics(
                generationIdentity = "G-PTI",
                predecessor = PredecessorWire.None,
                originAuthorityIdentity = GenerationFactPtiFixtureSupport.MODULE,
                attestsCurrent = true,
            )
        val candidate =
            GenerationFactTestSigning.buildCandidate(
                authority = authority,
                signerKeyVersion = GenerationFactPtiFixtureSupport.KEY_VERSION,
                trustBindingRevision = GenerationFactPtiFixtureSupport.ACTIVE_REVISION,
            )
        val outcome =
            VerificationBoundary(ProductionGenerationFactVerifier(h.lookup)).admit(candidate)
        assertTrue(outcome is VerificationOutcome.Success)
    }

    @Test
    fun pti_g12a_pv1VerifierWithProfileBackedLookup_retiredWithInclusion() {
        val h = GenerationFactPtiFixtureSupport.harness()
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority("G-PTI-R")
        val envelope =
            GenerationFactTestSigning.signAuthority(
                authority,
                GenerationFactPtiFixtureSupport.KEY_VERSION,
                GenerationFactPtiFixtureSupport.ACTIVE_REVISION,
            )
        val commitment =
            GenerationFactCanonicalCodec.computeSemanticDigest(envelope.authorityCanonicalBytes)
        val root = GenerationFactInclusionProofVerifier.merkleRoot(listOf(commitment))
        GenerationFactPtiFixtureSupport.accept(h, GenerationFactPtiFixtureSupport.retiredBindingPayload(root))
        val proof =
            GenerationFactInclusionProofVerifier.buildProof(listOf(commitment), commitment)
                ?: error("proof required")
        val bundle =
            GenerationFactVerificationBundle(
                signedFactBytes = envelope.toSignedFactBytes(),
                historicalInclusionProof = proof,
            )
        val candidate =
            GenerationFactTestSigning.buildCandidate(
                authority = authority,
                signerKeyVersion = GenerationFactPtiFixtureSupport.KEY_VERSION,
                trustBindingRevision = GenerationFactPtiFixtureSupport.ACTIVE_REVISION,
                inclusionProof = proof,
            ).copy(opaqueMaterial = bundle.encodeToVerificationMaterial())
        val outcome =
            VerificationBoundary(ProductionGenerationFactVerifier(h.lookup)).admit(candidate)
        assertTrue(outcome is VerificationOutcome.Success)
    }

    @Test
    fun pti_g13_atomicPersistSurvivesRestart() {
        val dir = Files.createTempDirectory("pti-trust")
        val file = dir.resolve("accepted.bin")
        val persistence = FileAcceptedTrustStatePersistence(file)
        val authenticator = FixtureProfileRevisionAuthenticator()
        val store1 = AcceptedLocalTrustStateStore(persistence)
        val acceptor1 = ProfileRevisionAcceptor(authenticator, store1)
        val payload = GenerationFactPtiFixtureSupport.activeBindingPayload(revision = 30)
        authenticator.allowPayload(payload)
        assertTrue(acceptor1.acceptPayload(payload) is ProfileRevisionAcceptResult.Accepted)

        val store2 = AcceptedLocalTrustStateStore(persistence)
        store2.reloadFromPersistence()
        assertEquals(30L, store2.currentSnapshot()?.taskProfileRevision)
    }

    @Test
    fun pti_g13_persistFailureDoesNotMutateAcceptedState() {
        val authenticator = FixtureProfileRevisionAuthenticator()
        val failing =
            object : AcceptedTrustStatePersistence {
                override fun save(state: AcceptedLocalTrustState) {
                    throw IOException("simulated persist failure")
                }

                override fun load(): AcceptedLocalTrustState? = null
            }
        val store = AcceptedLocalTrustStateStore(failing)
        val acceptor = ProfileRevisionAcceptor(authenticator, store)
        val payload = GenerationFactPtiFixtureSupport.activeBindingPayload()
        authenticator.allowPayload(payload)
        val result = acceptor.acceptPayload(payload)
        assertTrue(result is ProfileRevisionAcceptResult.PersistenceFailed)
        assertEquals(null, store.currentSnapshot())
    }
}
