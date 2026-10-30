package com.talkback.core.conference.runtime

/**
 * Profile 03 Q8 constants (E2b-06).
 * HealthAggregationWindowMs = evidence retention only (C-E2B06-03).
 */
object MediaHealthBoundaryConstants {
    const val HEALTH_AGGREGATION_WINDOW_MS: Long = 1_000L
}

/**
 * Frozen Q12 mapping entry injected for projection smoke / read-only feed.
 * P03 does not invent these mappings.
 */
data class FrozenQ12Mapping(
    val evidenceKind: RuntimeDegradationKind,
    /** Opaque hint string owned by Q12 contract — not a P03 Health enum. */
    val projectionHint: String,
)

/**
 * C-E2B06-02: read-only from Profile 03. Does not mutate authority or invent enums.
 */
interface Q12ProjectionSeam {
    fun mappings(): List<FrozenQ12Mapping>

    /**
     * Emit projection hints for currently active evidence that have a frozen mapping.
     * Unmapped evidence → omitted (evidence-only; C-E2B06-01).
     */
    fun project(activeEvidence: List<RuntimeDegradationEvidence>): List<String>
}

class InjectableQ12ProjectionSeam(
    private var mappings: List<FrozenQ12Mapping> = emptyList(),
) : Q12ProjectionSeam {
    fun setMappings(next: List<FrozenQ12Mapping>) {
        mappings = next
    }

    override fun mappings(): List<FrozenQ12Mapping> = mappings

    override fun project(activeEvidence: List<RuntimeDegradationEvidence>): List<String> {
        if (mappings.isEmpty()) return emptyList()
        val byKind = mappings.associateBy { it.evidenceKind }
        return activeEvidence.mapNotNull { e -> byKind[e.kind]?.projectionHint }.distinct()
    }
}

/**
 * Aggregates runtime evidence inside HealthAggregationWindowMs.
 * Clear = evidence no longer asserted — not authority/Health repair.
 */
class RuntimeEvidenceAggregator {
    private val items = mutableListOf<RuntimeDegradationEvidence>()

    fun record(evidence: RuntimeDegradationEvidence) {
        items += evidence
    }

    fun recordAll(batch: List<RuntimeDegradationEvidence>) {
        items += batch
    }

    /**
     * Active evidence within the aggregation window ending at [nowMs].
     * Expiration alone has no fence/authority/Health semantics (C-E2B06-03).
     */
    fun activeAt(nowMs: Long): List<RuntimeDegradationEvidence> {
        val earliest = nowMs - MediaHealthBoundaryConstants.HEALTH_AGGREGATION_WINDOW_MS
        return items.filter { it.atMs >= earliest && it.atMs <= nowMs }
    }

    /**
     * Clear evidence matching [predicate]. Does not repair Source/authority/Health.
     */
    fun clearWhere(predicate: (RuntimeDegradationEvidence) -> Boolean) {
        items.removeAll(predicate)
    }

    fun clearAll() {
        items.clear()
    }

    fun rawSize(): Int = items.size
}

/**
 * Profile 03 MUST NOT synthesize these from runtime evidence alone.
 * Used only as forbidden-inference labels in boundary tests.
 */
object ForbiddenP03HealthInferences {
    const val IMPAIRED = "IMPAIRED"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val PARTIAL = "PARTIAL"
    const val SOURCE_CONFLICT = "SOURCE_CONFLICT"
    const val NO_REMOTE_SOURCE = "NO_REMOTE_SOURCE"
}
