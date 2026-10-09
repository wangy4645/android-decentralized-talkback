package com.talkback.core.conference.transport

/**
 * ADR-0058 F9.3 — Shared Mix Timeline Normalization.
 *
 * Cross-source slot compare/filter only in shared mix domain M.
 * Per-source eligibility ([nextExpectedSourceSlot]) remains in source domain S.
 */
object SharedMixPlayoutSlotResolver {
    data class SourceMixCandidate(
        val sourceIdentity: String,
        val alignment: SourceMixPlayoutAlignment?,
        val bufferedSourceSlots: List<Long>,
        val nextExpectedSourceSlot: Long?,
        val mediaTimeMsForSourceSlot: (sourceSlot: Long) -> Long?,
    )

    data class ResolvedMixSlot(
        val mixSlot: Long,
        val mixSlotMediaTimeMs: Long,
    )

    fun mixSlotForSource(
        sourceSlot: Long,
        alignment: SourceMixPlayoutAlignment?,
    ): Long = alignment?.mixSlotForSource(sourceSlot) ?: sourceSlot

    /**
     * Earliest normalized mix slot playable at or before [targetMixSlot].
     * Cross-source min is min(normalized M), never min(raw S).
     */
    fun resolveAtOrBeforeTarget(
        targetMixSlot: Long,
        candidates: List<SourceMixCandidate>,
    ): ResolvedMixSlot? {
        var bestMixSlot: Long? = null
        var bestMediaTimeMs: Long? = null
        for (candidate in candidates) {
            val eligibleSourceSlots =
                candidate.bufferedSourceSlots.filter { slot ->
                    candidate.nextExpectedSourceSlot == null || slot >= candidate.nextExpectedSourceSlot
                }
            if (eligibleSourceSlots.isEmpty()) continue

            val normalized =
                eligibleSourceSlots.map { sourceSlot ->
                    mixSlotForSource(sourceSlot, candidate.alignment) to sourceSlot
                }
            val chosenMixSlot =
                normalized
                    .filter { (mixSlot, _) -> mixSlot <= targetMixSlot }
                    .minByOrNull { (mixSlot, _) -> mixSlot }
                    ?.first
                    ?: normalized.minByOrNull { (mixSlot, _) -> mixSlot }?.first
                    ?: continue
            val chosenSourceSlot =
                normalized.first { (mixSlot, _) -> mixSlot == chosenMixSlot }.second
            val mediaTimeMs =
                candidate.mediaTimeMsForSourceSlot(chosenSourceSlot) ?: continue
            if (bestMixSlot == null || chosenMixSlot < bestMixSlot) {
                bestMixSlot = chosenMixSlot
                bestMediaTimeMs = mediaTimeMs
            }
        }
        return if (bestMixSlot != null && bestMediaTimeMs != null) {
            ResolvedMixSlot(mixSlot = bestMixSlot, mixSlotMediaTimeMs = bestMediaTimeMs)
        } else {
            null
        }
    }
}
