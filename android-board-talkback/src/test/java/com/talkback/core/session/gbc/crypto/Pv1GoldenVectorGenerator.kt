package com.talkback.core.session.gbc.crypto

import com.talkback.core.session.gbc.GenerationFactCandidate
import com.talkback.core.session.gbc.trust.FixtureGenerationFactTrustLookup

/**
 * Frozen PV-E golden vector generator (test-only).
 * Signatures are deterministic for a fixed signing key + message.
 */
object Pv1GoldenVectorGenerator {
    data class Case(
        val name: String,
        val candidate: GenerationFactCandidate,
        val lookup: FixtureGenerationFactTrustLookup,
        val expectedOutcome: String,
    )

    fun cases(): List<Case> {
        val activeAuthority = GenerationFactPv1FixtureSupport.genesisAuthority("G-GOLDEN-ACTIVE")
        val activeCandidate =
            GenerationFactTestSigning.buildCandidate(
                activeAuthority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        val activeCase =
            Case(
                name = "active_success",
                candidate = activeCandidate,
                lookup = GenerationFactPv1FixtureSupport.activeTrustLookup(),
                expectedOutcome = "Success",
            )

        val histAuthority = GenerationFactPv1FixtureSupport.genesisAuthority("G-HIST")
        val histEnvelope =
            GenerationFactTestSigning.signAuthority(
                histAuthority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        val histCommitment =
            GenerationFactCanonicalCodec.computeSemanticDigest(histEnvelope.authorityCanonicalBytes)
        val histProof =
            GenerationFactInclusionProofVerifier.buildProof(listOf(histCommitment), histCommitment)!!
        val histCandidate =
            GenerationFactTestSigning.buildCandidate(
                histAuthority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
                inclusionProof = histProof,
            )
        val histCase =
            Case(
                name = "retired_verify_success",
                candidate = histCandidate,
                lookup = GenerationFactPv1FixtureSupport.retiredTrustLookup(listOf(histCommitment)),
                expectedOutcome = "Success",
            )

        val issuedAuthority = GenerationFactPv1FixtureSupport.genesisAuthority("G-GOLDEN-ISSUED")
        val issuedEnvelope =
            GenerationFactTestSigning.signAuthority(
                issuedAuthority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        val issuedCommitment =
            GenerationFactCanonicalCodec.computeSemanticDigest(issuedEnvelope.authorityCanonicalBytes)
        val forgedCandidate =
            GenerationFactTestSigning.buildCandidate(
                GenerationFactPv1FixtureSupport.genesisAuthority("G-GOLDEN-FORGED"),
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        val forgedCase =
            Case(
                name = "post_retirement_forgery_fail",
                candidate = forgedCandidate,
                lookup = GenerationFactPv1FixtureSupport.retiredTrustLookup(listOf(issuedCommitment)),
                expectedOutcome = "VerifyFail",
            )

        return listOf(histCase, activeCase, forgedCase)
    }
}
