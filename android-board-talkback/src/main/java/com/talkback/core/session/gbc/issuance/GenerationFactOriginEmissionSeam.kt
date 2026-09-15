package com.talkback.core.session.gbc.issuance

/**
 * Single observable emission seam for newly originated Facts (PV2-IA-T3).
 * Relay/re-provide of already-verified Facts MUST NOT use this gate.
 */
class GenerationFactOriginEmissionSeam(
    private val writer: GenerationFactIssuanceWriter,
    private val publishGate: GenerationFactIssuancePublishGate,
) {
    fun prepareIssuance(
        key: GenerationFactIssuanceKey,
        authority: com.talkback.core.session.gbc.crypto.GenerationFactCanonicalCodec.AuthoritySemantics,
        verificationContext: com.talkback.core.session.gbc.crypto.GenerationFactCanonicalCodec.VerificationContext,
    ): PrepareIssuanceResult = writer.prepareIssuance(key, authority, verificationContext)

    fun signAndFinalize(
        key: GenerationFactIssuanceKey,
        prepareId: String,
    ): FinalizeIssuanceResult = writer.signAndFinalize(key, prepareId)

    fun admitFirstPublish(
        key: GenerationFactIssuanceKey,
        factCommitment: ByteArray,
    ): PublishAdmissionResult = publishGate.admitFirstPublish(key, factCommitment)
}
