package com.talkback.core.session

import com.talkback.core.model.ModuleId

/**
 * #180 — session-scoped pairwise mesh admission obligation (not membership, not ICE owner).
 */
data class PairwiseMeshAdmissionObligation(
    val edge: PairwiseMeshEdgeKey,
    val offerer: ModuleId,
    val answerer: ModuleId,
    val signalingSatisfied: Boolean
)
