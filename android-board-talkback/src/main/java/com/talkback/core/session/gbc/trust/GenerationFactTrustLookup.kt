package com.talkback.core.session.gbc.trust

/**
 * Read-only trust evidence for Production Generation Fact verification (PV-A).
 * MUST NOT mutate profile state, rotate keys, or reach into GBC.
 */
enum class GenerationFactKeyState {
    PREPARED,
    ACTIVE,
    RETIRED_VERIFY,
    RETIRED_OFF,
    REVOKED,
}

data class GenerationFactTrustBinding(
    val moduleId: String,
    val signerKeyVersion: Long,
    val keyState: GenerationFactKeyState,
    val publicKeySpki: ByteArray,
    val activatedAtRevision: Long,
    val retiredVerifyAtRevision: Long?,
)

data class GenerationFactRetirementCheckpoint(
    val moduleId: String,
    val signerKeyVersion: Long,
    val trustBindingRevision: Long,
    val issuanceRoot: ByteArray,
)

sealed class GenerationFactTrustLookupResult {
    data class Found(
        val binding: GenerationFactTrustBinding,
    ) : GenerationFactTrustLookupResult()

    data object UnknownModule : GenerationFactTrustLookupResult()

    data object UnknownKeyVersion : GenerationFactTrustLookupResult()

    data object InvalidTrustBindingRevision : GenerationFactTrustLookupResult()
}

interface GenerationFactTrustLookup {
    fun lookupBinding(
        moduleId: String,
        signerKeyVersion: Long,
        trustBindingRevision: Long,
    ): GenerationFactTrustLookupResult

    fun retirementCheckpoint(
        moduleId: String,
        signerKeyVersion: Long,
    ): GenerationFactRetirementCheckpoint?
}
