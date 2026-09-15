package com.talkback.core.conference.runtime

/**
 * C-E2B02-01 Decode eligibility gate (Top-K-before-decode; no Opus in this slice).
 *
 * Requires:
 *   current AdmittedMediaSource incarnation installed
 *   AND incarnation not HARD FENCED
 *   AND Source currently in Top-K
 *
 * Packet arrival / V-level update alone MUST NOT satisfy eligibility.
 */
object DecodeEligibilityGate {
    fun isDecodeEligible(
        sourceIdentity: String,
        incarnationId: Long,
        registry: AdmittedMediaSourceRegistry,
        topK: TopKSelector.SelectionState,
    ): Boolean {
        if (!registry.isInstalledExecutable(sourceIdentity, incarnationId)) return false
        return topK.members.any {
            it.sourceIdentity == sourceIdentity && it.incarnationId == incarnationId
        }
    }
}
