package com.talkback.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * ADR-0057 Generation Fact wire payloads (implementation detail encoding).
 *
 * S1 discriminants live in [SignalType].
 * S2 semantics preserved; concrete field names NOT permanently contract-frozen.
 * S3 delivery-context is orchestration-only — never Fact provenance.
 * S4 opaque verificationMaterial is a lossless carrier — never Verification SUCCESS.
 *
 * Codec MUST produce [com.talkback.core.session.gbc.GenerationFactCandidate] only,
 * never AuthoritativeGenerationFact.
 */
data class GenerationFactRequestPayload(
    val channelId: String,
    val correlationId: String,
    val requesterModuleId: String,
    val targetHolderModuleId: String? = null,
) {
    fun encode(): String =
        JSONObject()
            .put("channelId", channelId)
            .put("correlationId", correlationId)
            .put("requesterModuleId", requesterModuleId)
            .putOpt("targetHolderModuleId", targetHolderModuleId)
            .toString()

    companion object {
        fun decode(raw: String): GenerationFactRequestPayload? =
            runCatching {
                val json = JSONObject(raw)
                GenerationFactRequestPayload(
                    channelId = json.getString("channelId"),
                    correlationId = json.getString("correlationId"),
                    requesterModuleId = json.getString("requesterModuleId"),
                    targetHolderModuleId =
                        json.optString("targetHolderModuleId").takeIf { it.isNotBlank() },
                )
            }.getOrNull()
    }
}

data class GenerationFactResponseCandidatePayload(
    // S3 — delivery context (orchestration only)
    val channelId: String,
    val correlationId: String,
    val requesterModuleId: String,
    val responderModuleId: String,
    // S2 — authoritative semantics candidates
    val generationIdentity: String,
    /**
     * Predecessor relationship where applicable.
     * - [PredecessorWire.Absent]: relationship not applicable on this Fact
     * - [PredecessorWire.None]: genesis / no predecessor
     * - [PredecessorWire.Id]: explicit predecessor identity
     */
    val predecessor: PredecessorWire,
    val originAuthorityIdentity: String,
    /** Current/successor attestation semantics (representation = impl detail). */
    val attestsCurrent: Boolean,
    val semanticDigest: String,
    val resolvesConflictSet: Set<String> = emptySet(),
    // S4 — opaque verification material (lossless; ≠ SUCCESS)
    val verificationMaterial: String,
) {
    fun encode(): String {
        val json =
            JSONObject()
                .put("channelId", channelId)
                .put("correlationId", correlationId)
                .put("requesterModuleId", requesterModuleId)
                .put("responderModuleId", responderModuleId)
                .put("generationIdentity", generationIdentity)
                .put("originAuthorityIdentity", originAuthorityIdentity)
                .put("attestsCurrent", attestsCurrent)
                .put("semanticDigest", semanticDigest)
                .put("verificationMaterial", verificationMaterial)
        when (predecessor) {
            PredecessorWire.Absent -> Unit
            PredecessorWire.None -> json.put("predecessorGenerationIdentity", JSONObject.NULL)
            is PredecessorWire.Id ->
                json.put("predecessorGenerationIdentity", predecessor.generationIdentity)
        }
        if (resolvesConflictSet.isNotEmpty()) {
            val arr = JSONArray()
            resolvesConflictSet.forEach { arr.put(it) }
            json.put("resolvesConflictSet", arr)
        }
        return json.toString()
    }

    companion object {
        fun decode(raw: String): GenerationFactResponseCandidatePayload? =
            runCatching {
                val json = JSONObject(raw)
                val generationIdentity = json.getString("generationIdentity")
                val semanticDigest = json.getString("semanticDigest")
                if (generationIdentity.isBlank() || semanticDigest.isBlank()) return null
                if (!json.has("originAuthorityIdentity")) return null
                if (!json.has("attestsCurrent")) return null
                if (!json.has("verificationMaterial")) return null // slot must exist (may be empty)
                val predecessor =
                    if (!json.has("predecessorGenerationIdentity")) {
                        PredecessorWire.Absent
                    } else if (json.isNull("predecessorGenerationIdentity")) {
                        PredecessorWire.None
                    } else {
                        PredecessorWire.Id(json.getString("predecessorGenerationIdentity"))
                    }
                val resolves = linkedSetOf<String>()
                json.optJSONArray("resolvesConflictSet")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let { resolves += it }
                    }
                }
                GenerationFactResponseCandidatePayload(
                    channelId = json.getString("channelId"),
                    correlationId = json.getString("correlationId"),
                    requesterModuleId = json.getString("requesterModuleId"),
                    responderModuleId = json.getString("responderModuleId"),
                    generationIdentity = generationIdentity,
                    predecessor = predecessor,
                    originAuthorityIdentity = json.getString("originAuthorityIdentity"),
                    attestsCurrent = json.getBoolean("attestsCurrent"),
                    semanticDigest = semanticDigest,
                    resolvesConflictSet = resolves,
                    verificationMaterial = json.getString("verificationMaterial"),
                )
            }.getOrNull()
    }
}

sealed class PredecessorWire {
    /** Relationship not applicable for this Fact body. */
    data object Absent : PredecessorWire()

    /** Genesis / no predecessor (relationship applicable). */
    data object None : PredecessorWire()

    data class Id(
        val generationIdentity: String,
    ) : PredecessorWire()
}

data class GenerationFactResponseInsufficientPayload(
    val channelId: String,
    val correlationId: String,
    val requesterModuleId: String,
    val responderModuleId: String,
) {
    fun encode(): String =
        JSONObject()
            .put("channelId", channelId)
            .put("correlationId", correlationId)
            .put("requesterModuleId", requesterModuleId)
            .put("responderModuleId", responderModuleId)
            .toString()

    companion object {
        fun decode(raw: String): GenerationFactResponseInsufficientPayload? =
            runCatching {
                val json = JSONObject(raw)
                GenerationFactResponseInsufficientPayload(
                    channelId = json.getString("channelId"),
                    correlationId = json.getString("correlationId"),
                    requesterModuleId = json.getString("requesterModuleId"),
                    responderModuleId = json.getString("responderModuleId"),
                )
            }.getOrNull()
    }
}
