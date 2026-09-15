package com.talkback.core.session.gbc.crypto

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * One-shot exporter for PV-E golden vectors. Run manually when canonical bytes change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GenerationFactGoldenVectorExportTest {
    @Ignore("Manual PV-E golden vector export helper")
    @Test
    fun exportGoldenVectorsToStdout() {
        val vectors = JsonArray()

        val activeAuthority = GenerationFactPv1FixtureSupport.genesisAuthority("G-GOLDEN-ACTIVE")
        val activeEnvelope =
            GenerationFactTestSigning.signAuthority(
                activeAuthority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        vectors.add(
            vector(
                name = "active_success",
                authority = activeAuthority,
                envelope = activeEnvelope,
                trustMode = "active",
                expectedOutcome = "Success",
                signatureValid = true,
            ),
        )

        val histAuthority = GenerationFactPv1FixtureSupport.genesisAuthority("G-GOLDEN-HIST")
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
        vectors.add(
            vector(
                name = "retired_verify_success",
                authority = histAuthority,
                envelope = histEnvelope,
                trustMode = "retired_with_inclusion",
                expectedOutcome = "Success",
                signatureValid = true,
                inclusionProof = histProof,
            ),
        )

        val issuedAuthority = GenerationFactPv1FixtureSupport.genesisAuthority("G-GOLDEN-ISSUED")
        val issuedEnvelope =
            GenerationFactTestSigning.signAuthority(
                issuedAuthority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        val forgedAuthority = GenerationFactPv1FixtureSupport.genesisAuthority("G-GOLDEN-FORGED")
        val forgedEnvelope =
            GenerationFactTestSigning.signAuthority(
                forgedAuthority,
                GenerationFactPv1FixtureSupport.KEY_VERSION,
                GenerationFactPv1FixtureSupport.ACTIVE_REVISION,
            )
        val issuedCommitment =
            GenerationFactCanonicalCodec.computeSemanticDigest(issuedEnvelope.authorityCanonicalBytes)
        vectors.add(
            vector(
                name = "post_retirement_forgery_fail",
                authority = forgedAuthority,
                envelope = forgedEnvelope,
                trustMode = "retired_without_inclusion",
                expectedOutcome = "VerifyFail",
                signatureValid = true,
                issuedAuthorityCanonicalBytes = issuedEnvelope.authorityCanonicalBytes,
            ),
        )

        val root = JsonObject()
        root.addProperty("schema", "adr0057-pv1-golden-vectors-v1")
        root.add("vectors", vectors)
        println(GsonBuilder().setPrettyPrinting().create().toJson(root))
    }

    private fun vector(
        name: String,
        authority: GenerationFactCanonicalCodec.AuthoritySemantics,
        envelope: SignedGenerationFactEnvelope,
        trustMode: String,
        expectedOutcome: String,
        signatureValid: Boolean,
        inclusionProof: ByteArray? = null,
        issuedAuthorityCanonicalBytes: ByteArray? = null,
    ): JsonObject {
        val context = GenerationFactCanonicalCodec.decodeVerificationContext(envelope.verificationContextBytes)!!
        val obj = JsonObject()
        obj.addProperty("name", name)
        obj.addProperty("generationIdentity", authority.generationIdentity)
        obj.addProperty("originAuthorityIdentity", authority.originAuthorityIdentity)
        obj.addProperty("signerKeyVersion", context.signerKeyVersion)
        obj.addProperty("trustBindingRevision", context.trustBindingRevision)
        obj.addProperty(
            "authorityCanonicalBytesB64",
            Base64.getEncoder().encodeToString(envelope.authorityCanonicalBytes),
        )
        obj.addProperty(
            "verificationContextBytesB64",
            Base64.getEncoder().encodeToString(envelope.verificationContextBytes),
        )
        obj.addProperty("signatureRsB64", Base64.getEncoder().encodeToString(envelope.signatureRs))
        obj.addProperty(
            "semanticDigestHex",
            GenerationFactCanonicalCodec.semanticDigestHex(envelope.authorityCanonicalBytes),
        )
        obj.addProperty("signatureValid", signatureValid)
        obj.addProperty("trustMode", trustMode)
        obj.addProperty("expectedOutcome", expectedOutcome)
        inclusionProof?.let {
            obj.addProperty("historicalInclusionProofB64", Base64.getEncoder().encodeToString(it))
        }
        issuedAuthorityCanonicalBytes?.let {
            obj.addProperty("issuedAuthorityCanonicalBytesB64", Base64.getEncoder().encodeToString(it))
        }
        return obj
    }
}
