package com.talkback.core.session.gbc.issuance

import com.talkback.core.session.gbc.crypto.GenerationFactCanonicalCodec
import com.talkback.core.session.gbc.crypto.GenerationFactInclusionProofVerifier
import com.talkback.core.session.gbc.crypto.GenerationFactPv1FixtureSupport
import com.talkback.core.session.gbc.crypto.GenerationFactTestSigning
import com.talkback.core.session.gbc.crypto.GenerationFactVerificationBundle
import com.talkback.core.session.gbc.crypto.ProductionGenerationFactVerifier
import com.talkback.core.session.gbc.VerificationBoundary
import com.talkback.core.session.gbc.VerificationOutcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class Adr0057Pv2IssuanceFixturesTest {
    private val key =
        GenerationFactIssuanceKey(
            moduleId = GenerationFactPv1FixtureSupport.MODULE_M01,
            signerKeyVersion = GenerationFactPv1FixtureSupport.KEY_VERSION,
        )

    private val signer =
        GenerationFactIssuanceSigner { authorityBytes, contextBytes ->
            GenerationFactTestSigning.signMessage(
                GenerationFactCanonicalCodec.signatureInput(authorityBytes, contextBytes),
            )
        }

    private fun harness(store: DurableGenerationFactIssuanceStore = DurableGenerationFactIssuanceStore()): Pv2Harness {
        val writer = GenerationFactIssuanceWriter(store, signer)
        val gate = GenerationFactIssuancePublishGate(store)
        val retirement = GenerationFactRetirementCoordinator(store, writer)
        return Pv2Harness(store, writer, gate, retirement)
    }

    @Test
    fun pv2_g1_finalizeBeforePublish_enforced() {
        val h = harness()
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority("G-PV2-1")
        val prepared = h.writer.prepareIssuance(key, authority, verificationContext()) as PrepareIssuanceResult.Prepared

        assertTrue(h.gate.admitFirstPublish(key, prepared.factCommitment) is PublishAdmissionResult.NotFinalized)

        val finalized = h.writer.signAndFinalize(key, prepared.prepareId) as FinalizeIssuanceResult.Finalized
        val admitted = h.gate.admitFirstPublish(key, finalized.record.factCommitment) as PublishAdmissionResult.Admitted
        assertArrayEquals(finalized.record.signedFactBytes, admitted.signedFactBytes)
    }

    @Test
    fun pv2_g2_publishedFactHasFinalizedRecord() {
        val h = harness()
        val finalized = issueFact(h, "G-PV2-2")
        val stored = h.store.findFinalized(key, finalized.record.factCommitment)
        assertNotNull(stored)
        assertArrayEquals(finalized.record.signedFactBytes, stored!!.signedFactBytes)
    }

    @Test
    fun pv2_g3_idempotentRepublishExactBytes() {
        val h = harness()
        val finalized = issueFact(h, "G-PV2-3")
        val first = h.gate.admitFirstPublish(key, finalized.record.factCommitment) as PublishAdmissionResult.Admitted
        val second = h.gate.admitFirstPublish(key, finalized.record.factCommitment) as PublishAdmissionResult.Admitted
        assertArrayEquals(first.signedFactBytes, second.signedFactBytes)
    }

    @Test
    fun pv2_g4_preparedUnsignedExcludedFromCheckpoint() {
        val h = harness()
        issueFact(h, "G-FINALIZED")
        h.writer.prepareIssuance(key, GenerationFactPv1FixtureSupport.genesisAuthority("G-PREPARED"), verificationContext())
        h.retirement.beginRetirementPrepare(key)
        val proposed = h.retirement.proposeTerminalHead(key) as TerminalHeadResult.Proposed
        assertEquals(1, proposed.head.finalizedCount)
        assertTrue(h.store.preparedRecords(key).isEmpty())
    }

    @Test
    fun pv2_g5_retirementFenceAndTerminalHead() {
        val h = harness()
        issueFact(h, "G-RETIRE")
        assertEquals(RetirementPrepareResult.Started, h.retirement.beginRetirementPrepare(key))
        val proposed = h.retirement.proposeTerminalHead(key) as TerminalHeadResult.Proposed
        assertArrayEquals(h.store.merkleRoot(key), proposed.head.issuanceRoot)
        assertEquals(1, proposed.head.finalizedCount)
    }

    @Test
    fun pv2_g6_originCannotBindCheckpoint_profileAuthorityDeferred() {
        val h = harness()
        issueFact(h, "G-PROP")
        h.retirement.beginRetirementPrepare(key)
        val proposed = h.retirement.proposeTerminalHead(key) as TerminalHeadResult.Proposed
        assertNotNull(h.store.proposedTerminalHead(key))
        assertEquals(IssuanceRetirementPhase.TERMINAL_HEAD_PROPOSED, h.store.retirementPhase(key))
        assertEquals(32, proposed.head.issuanceRoot.size)
    }

    @Test
    fun pv2_g7_postTerminalHeadFinalizeRejected() {
        val h = harness()
        issueFact(h, "G-OLD")
        h.retirement.beginRetirementPrepare(key)
        h.retirement.proposeTerminalHead(key)

        val prepared =
            h.writer.prepareIssuance(
                key,
                GenerationFactPv1FixtureSupport.genesisAuthority("G-NEW"),
                verificationContext(),
            )
        assertTrue(prepared is PrepareIssuanceResult.Retired)
    }

    @Test
    fun pv2_g8_retirementAbortThenFreshHead() {
        val h = harness()
        issueFact(h, "G-A")
        h.retirement.beginRetirementPrepare(key)
        val first = h.retirement.proposeTerminalHead(key) as TerminalHeadResult.Proposed
        assertTrue(h.retirement.abortRetirement(key))

        issueFact(h, "G-B")
        h.retirement.beginRetirementPrepare(key)
        val second = h.retirement.proposeTerminalHead(key) as TerminalHeadResult.Proposed
        assertFalse(first.head.issuanceRoot.contentEquals(second.head.issuanceRoot))
        assertEquals(2, second.head.finalizedCount)
    }

    @Test
    fun pv2_g9_publishGateBlocksNonFinalized() {
        val h = harness()
        val prepared =
            h.writer.prepareIssuance(
                key,
                GenerationFactPv1FixtureSupport.genesisAuthority("G-GATE"),
                verificationContext(),
            ) as PrepareIssuanceResult.Prepared
        assertTrue(h.gate.admitFirstPublish(key, prepared.factCommitment) is PublishAdmissionResult.NotFinalized)
    }

    @Test
    fun pv2_g10_pv1Regression_endToEndWithIssuancePrefix() {
        val h = harness()
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority("G-E2E")
        val finalized = issueFact(h, authority)
        val commitments = h.store.finalizedRecords(key).map { it.factCommitment }
        val proof =
            GenerationFactInclusionProofVerifier.buildProof(commitments, finalized.record.factCommitment)
                ?: error("proof required")
        val bundle =
            GenerationFactVerificationBundle(
                signedFactBytes = finalized.record.signedFactBytes,
                historicalInclusionProof = proof,
            )
        h.retirement.beginRetirementPrepare(key)
        h.retirement.proposeTerminalHead(key)
        val lookup = GenerationFactPv1FixtureSupport.retiredTrustLookup(commitments)
        val candidate =
            GenerationFactTestSigning.buildCandidate(
                authority = authority,
                signerKeyVersion = GenerationFactPv1FixtureSupport.KEY_VERSION,
                trustBindingRevision = GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
                inclusionProof = proof,
            ).copy(opaqueMaterial = bundle.encodeToVerificationMaterial())
        val outcome = VerificationBoundary(ProductionGenerationFactVerifier(lookup)).admit(candidate)
        assertTrue(outcome is VerificationOutcome.Success)
    }

    @Test
    fun pv2_g11_nonFinalizedSignatureCannotPublish() {
        val h = harness()
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority("G-NF")
        val prepared =
            h.writer.prepareIssuance(key, authority, verificationContext()) as PrepareIssuanceResult.Prepared
        val envelope =
            GenerationFactTestSigning.signAuthority(
                authority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        assertTrue(h.gate.admitFirstPublish(key, prepared.factCommitment) is PublishAdmissionResult.NotFinalized)
        assertTrue(
            h.gate.admitFirstPublish(
                key,
                GenerationFactCanonicalCodec.computeSemanticDigest(envelope.authorityCanonicalBytes),
            ) is PublishAdmissionResult.NotFinalized,
        )
    }

    @Test
    fun pv2_g12_unresolvedPreparedBlocksTerminalHeadWhileInFlight() {
        val signStarted = CountDownLatch(1)
        val allowSignComplete = CountDownLatch(1)
        val blockingSigner =
            GenerationFactIssuanceSigner { authorityBytes, contextBytes ->
                signStarted.countDown()
                check(allowSignComplete.await(3, TimeUnit.SECONDS))
                GenerationFactTestSigning.signMessage(
                    GenerationFactCanonicalCodec.signatureInput(authorityBytes, contextBytes),
                )
            }
        val store = DurableGenerationFactIssuanceStore()
        val writer = GenerationFactIssuanceWriter(store, blockingSigner)
        val retirement = GenerationFactRetirementCoordinator(store, writer)
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority("G-RACE")
        val prepared = writer.prepareIssuance(key, authority, verificationContext()) as PrepareIssuanceResult.Prepared
        retirement.beginRetirementPrepare(key)
        val pool = Executors.newSingleThreadExecutor()
        val finalizeResult = AtomicReference<FinalizeIssuanceResult>()
        try {
            pool.submit {
                finalizeResult.set(writer.signAndFinalize(key, prepared.prepareId))
            }
            assertTrue(signStarted.await(3, TimeUnit.SECONDS))
            assertTrue(retirement.proposeTerminalHead(key) is TerminalHeadResult.UnresolvedPrepared)
            allowSignComplete.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
            assertTrue(finalizeResult.get() is FinalizeIssuanceResult.Finalized)
            assertTrue(retirement.proposeTerminalHead(key) is TerminalHeadResult.Proposed)
        } finally {
            allowSignComplete.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun pv2_g13_crashDuringFinalizePersist_noPartialFinalizedRecord() {
        val dir = Files.createTempDirectory("pv2-crash")
        val delegate = FileSnapshotIssuancePersistence(dir)
        val crashing =
            object : IssuanceStatePersistence {
                var crash = true
                override fun save(
                    key: GenerationFactIssuanceKey,
                    state: KeyIssuanceState,
                ) {
                    if (crash) throw IOException("simulated crash during FINALIZE persist")
                    delegate.save(key, state)
                }

                override fun load(key: GenerationFactIssuanceKey): KeyIssuanceState? = delegate.load(key)
            }
        val store = DurableGenerationFactIssuanceStore(crashing)
        val writer = GenerationFactIssuanceWriter(store, signer)
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority("G-CRASH")
        crashing.crash = false
        val prepared = writer.prepareIssuance(key, authority, verificationContext()) as PrepareIssuanceResult.Prepared
        crashing.crash = true
        assertTrue(runCatching { writer.signAndFinalize(key, prepared.prepareId) }.isFailure)
        val recovered = DurableGenerationFactIssuanceStore(delegate)
        recovered.reloadFromPersistence(key)
        assertTrue(recovered.finalizedRecords(key).isEmpty())
        assertEquals(1, recovered.preparedRecords(key).size)

        crashing.crash = false
        val writer2 = GenerationFactIssuanceWriter(recovered, signer)
        val finalized = writer2.signAndFinalize(key, prepared.prepareId) as FinalizeIssuanceResult.Finalized
        assertNotNull(finalized.record.signedFactBytes)
        recovered.reloadFromPersistence(key)
        val stored = recovered.findFinalized(key, finalized.record.factCommitment)
        assertNotNull(stored)
        assertArrayEquals(finalized.record.signedFactBytes, stored!!.signedFactBytes)
    }

    @Test
    fun pv2_g14_identicalFinalizeIdempotent_noDuplicateMerkleLeaf() {
        val h = harness()
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority("G-IDEM")
        val firstPrepared = h.writer.prepareIssuance(key, authority, verificationContext()) as PrepareIssuanceResult.Prepared
        val first = h.writer.signAndFinalize(key, firstPrepared.prepareId) as FinalizeIssuanceResult.Finalized
        val secondPrepared = h.writer.prepareIssuance(key, authority, verificationContext()) as PrepareIssuanceResult.Prepared
        val replay =
            h.store.finalize(key, secondPrepared.prepareId, first.record.signedFactBytes) as FinalizeIssuanceResult.Finalized
        assertTrue(replay.idempotentReplay)
        assertEquals(1, h.store.finalizedRecords(key).size)
        assertEquals(1, h.store.merkleRoot(key)?.let { 1 } ?: 0)
    }

    @Test
    fun pv2_g15_noPreFinalizeEmissionPath() {
        val h = harness()
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority("G-NOEMIT")
        val prepared =
            h.writer.prepareIssuance(key, authority, verificationContext()) as PrepareIssuanceResult.Prepared
        val envelope =
            GenerationFactTestSigning.signAuthority(
                authority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        assertTrue(h.gate.admitFirstPublish(key, prepared.factCommitment) is PublishAdmissionResult.NotFinalized)
        assertTrue(
            h.gate.admitFirstPublish(
                key,
                GenerationFactCanonicalCodec.computeSemanticDigest(envelope.authorityCanonicalBytes),
            ) is PublishAdmissionResult.NotFinalized,
        )
    }

    @Test
    fun pv2_g16_fenceRejectsPostFencePrepareFinalize() {
        val h = harness()
        h.retirement.beginRetirementPrepare(key)
        val prepared =
            h.writer.prepareIssuance(
                key,
                GenerationFactPv1FixtureSupport.genesisAuthority("G-FENCE"),
                verificationContext(),
            ) as PrepareIssuanceResult.Prepared
        val finalize = h.writer.signAndFinalize(key, prepared.prepareId)
        assertTrue(finalize is FinalizeIssuanceResult.FenceRejected)
    }

    @Test
    fun pv2_integrityConflict_differentBytesSameCommitment() {
        val h = harness()
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority("G-CONFLICT")
        val prepared = h.writer.prepareIssuance(key, authority, verificationContext()) as PrepareIssuanceResult.Prepared
        val firstBytes =
            GenerationFactTestSigning.signAuthority(
                authority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            ).toSignedFactBytes()
        h.store.finalize(key, prepared.prepareId, firstBytes)
        val prepared2 =
            h.writer.prepareIssuance(key, authority, verificationContext()) as PrepareIssuanceResult.Prepared
        val tampered = firstBytes.copyOf()
        tampered[tampered.size - 1] = (tampered.last().toInt() xor 0x01).toByte()
        val conflict = h.store.finalize(key, prepared2.prepareId, tampered)
        assertTrue(conflict is FinalizeIssuanceResult.IntegrityConflict)
    }

    private data class Pv2Harness(
        val store: DurableGenerationFactIssuanceStore,
        val writer: GenerationFactIssuanceWriter,
        val gate: GenerationFactIssuancePublishGate,
        val retirement: GenerationFactRetirementCoordinator,
    )

    private fun issueFact(
        h: Pv2Harness,
        generationId: String,
    ): FinalizeIssuanceResult.Finalized {
        val authority = GenerationFactPv1FixtureSupport.genesisAuthority(generationId)
        return issueFact(h, authority)
    }

    private fun issueFact(
        h: Pv2Harness,
        authority: GenerationFactCanonicalCodec.AuthoritySemantics,
    ): FinalizeIssuanceResult.Finalized {
        val prepared = h.writer.prepareIssuance(key, authority, verificationContext()) as PrepareIssuanceResult.Prepared
        return h.writer.signAndFinalize(key, prepared.prepareId) as FinalizeIssuanceResult.Finalized
    }

    private fun verificationContext(): GenerationFactCanonicalCodec.VerificationContext =
        GenerationFactCanonicalCodec.VerificationContext(
            originAuthorityIdentity = GenerationFactPv1FixtureSupport.MODULE_M01,
            signerKeyVersion = GenerationFactPv1FixtureSupport.KEY_VERSION,
            trustBindingRevision = GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
        )
}
