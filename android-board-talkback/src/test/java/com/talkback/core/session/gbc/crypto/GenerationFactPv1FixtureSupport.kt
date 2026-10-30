package com.talkback.core.session.gbc.crypto

import com.talkback.core.model.PredecessorWire
import com.talkback.core.session.gbc.trust.FixtureGenerationFactTrustLookup
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.GenerationFactRetirementCheckpoint
import com.talkback.core.session.gbc.trust.GenerationFactTrustBinding

object GenerationFactPv1FixtureSupport {
    const val MODULE_M01 = "M01"
    const val KEY_VERSION: Long = 1
    const val ACTIVE_REVISION: Long = 10
    const val RETIRED_REVISION: Long = 20

    fun activeTrustLookup(): FixtureGenerationFactTrustLookup {
        val lookup = FixtureGenerationFactTrustLookup()
        lookup.putCurrentBinding(activeBinding())
        return lookup
    }

    fun retiredTrustLookup(
        issuedCommitments: List<ByteArray>,
    ): FixtureGenerationFactTrustLookup {
        val lookup = activeTrustLookup()
        lookup.putCurrentBinding(retiredBinding())
        val root = GenerationFactInclusionProofVerifier.merkleRoot(issuedCommitments)
        lookup.putCheckpoint(
            GenerationFactRetirementCheckpoint(
                moduleId = MODULE_M01,
                signerKeyVersion = KEY_VERSION,
                trustBindingRevision = RETIRED_REVISION,
                issuanceRoot = root,
            ),
        )
        return lookup
    }

    fun genesisAuthority(generationIdentity: String = "G1"): GenerationFactCanonicalCodec.AuthoritySemantics =
        GenerationFactCanonicalCodec.AuthoritySemantics(
            generationIdentity = generationIdentity,
            predecessor = PredecessorWire.None,
            originAuthorityIdentity = MODULE_M01,
            attestsCurrent = true,
        )

    private fun activeBinding(): GenerationFactTrustBinding =
        GenerationFactTrustBinding(
            moduleId = MODULE_M01,
            signerKeyVersion = KEY_VERSION,
            keyState = GenerationFactKeyState.ACTIVE,
            publicKeySpki = GenerationFactTestSigning.publicKeySpki,
            activatedAtRevision = ACTIVE_REVISION,
            retiredVerifyAtRevision = null,
        )

    private fun retiredBinding(): GenerationFactTrustBinding =
        activeBinding().copy(
            keyState = GenerationFactKeyState.RETIRED_VERIFY,
            retiredVerifyAtRevision = RETIRED_REVISION,
        )
}
