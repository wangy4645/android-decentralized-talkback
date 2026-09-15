package com.talkback.core.session.gbc

import com.talkback.core.model.GenerationFactResponseCandidatePayload
import com.talkback.core.model.PredecessorWire

/**
 * Wire codec boundary: payload ↔ [GenerationFactCandidate] only.
 * MUST NOT construct [AuthoritativeGenerationFact] (authority bypass).
 */
object GenerationFactWireCodec {
    fun toCandidate(payload: GenerationFactResponseCandidatePayload): GenerationFactCandidate {
        val predecessorIdentity =
            when (val p = payload.predecessor) {
                PredecessorWire.Absent -> null
                PredecessorWire.None -> null
                is PredecessorWire.Id -> p.generationIdentity
            }
        return GenerationFactCandidate(
            claimedFactIdentity = payload.semanticDigest,
            claimedGenerationIdentity = payload.generationIdentity,
            claimedPredecessorGenerationIdentity = predecessorIdentity,
            claimedOriginAuthorityIdentity = payload.originAuthorityIdentity,
            claimedAttestsCurrent = payload.attestsCurrent,
            claimedSemanticDigest = payload.semanticDigest,
            opaqueMaterial = payload.verificationMaterial,
        )
    }

    fun fromAcceptedFact(
        channelId: String,
        correlationId: String,
        requesterModuleId: String,
        responderModuleId: String,
        fact: AuthoritativeGenerationFact,
        verificationMaterial: String,
    ): GenerationFactResponseCandidatePayload {
        val predecessor =
            when {
                fact.predecessorGenerationIdentity == null -> PredecessorWire.None
                else -> PredecessorWire.Id(fact.predecessorGenerationIdentity)
            }
        return GenerationFactResponseCandidatePayload(
            channelId = channelId,
            correlationId = correlationId,
            requesterModuleId = requesterModuleId,
            responderModuleId = responderModuleId,
            generationIdentity = fact.generationIdentity,
            predecessor = predecessor,
            originAuthorityIdentity = fact.originAuthorityIdentity,
            attestsCurrent = fact.attestsCurrent,
            semanticDigest = fact.semanticDigest,
            resolvesConflictSet = fact.resolvesConflictSet,
            verificationMaterial = verificationMaterial,
        )
    }
}
