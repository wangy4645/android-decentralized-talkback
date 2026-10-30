package com.talkback.core.session.gbc.trust

/**
 * Fixture-only trust lookup for PV-1 conformance harness.
 * MUST NOT be wired as production authority.
 */
class FixtureGenerationFactTrustLookup : GenerationFactTrustLookup {
    private val currentBindings = linkedMapOf<Pair<String, Long>, GenerationFactTrustBinding>()
    private val checkpoints = linkedMapOf<Pair<String, Long>, GenerationFactRetirementCheckpoint>()

    fun putCurrentBinding(binding: GenerationFactTrustBinding) {
        currentBindings[binding.moduleId to binding.signerKeyVersion] = binding
    }

    fun putCheckpoint(checkpoint: GenerationFactRetirementCheckpoint) {
        checkpoints[checkpoint.moduleId to checkpoint.signerKeyVersion] = checkpoint
    }

    override fun lookupBinding(
        moduleId: String,
        signerKeyVersion: Long,
        trustBindingRevision: Long,
    ): GenerationFactTrustLookupResult {
        val binding = currentBindings[moduleId to signerKeyVersion]
            ?: return when {
                currentBindings.keys.none { it.first == moduleId } ->
                    GenerationFactTrustLookupResult.UnknownModule
                else -> GenerationFactTrustLookupResult.UnknownKeyVersion
            }
        if (trustBindingRevision < binding.activatedAtRevision) {
            return GenerationFactTrustLookupResult.InvalidTrustBindingRevision
        }
        val retiredAt = binding.retiredVerifyAtRevision
        if (retiredAt != null && trustBindingRevision > retiredAt) {
            return GenerationFactTrustLookupResult.InvalidTrustBindingRevision
        }
        return GenerationFactTrustLookupResult.Found(binding)
    }

    override fun retirementCheckpoint(
        moduleId: String,
        signerKeyVersion: Long,
    ): GenerationFactRetirementCheckpoint? = checkpoints[moduleId to signerKeyVersion]
}
