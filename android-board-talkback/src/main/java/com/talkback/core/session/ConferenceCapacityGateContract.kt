package com.talkback.core.session

/**
 * Gate 3 / Gate 4 capacity shape. Validates topology; does not admit, publish, or recover.
 * See docs/analysis/0056-capacity-gate-8-10-contract.md
 *
 * Not ADR-0056 Phase 3 (Health → UI).
 */
sealed class ConferenceCapacityEvaluation {
    data object MeshAdmitted : ConferenceCapacityEvaluation()
    data class AnchorAdmitted(val expectedEdgeCount: Int) : ConferenceCapacityEvaluation()
    data class Rejected(val reason: String) : ConferenceCapacityEvaluation()
}

object ConferenceCapacityGateContract {

    const val ANCHOR_THRESHOLD: Int = 4
    const val GATE4_MAX_PARTICIPANTS: Int = 10

    fun expectedStarEdgeCount(memberCount: Int): Int = memberCount - 1

    fun evaluate(snapshot: ConferenceTopologySnapshot): ConferenceCapacityEvaluation {
        val n = snapshot.members.distinct().size
        if (n > GATE4_MAX_PARTICIPANTS) {
            return ConferenceCapacityEvaluation.Rejected("above Gate 4 architecture limit")
        }
        return when (snapshot.topologyMode) {
            ConferenceTopologyMode.MESH -> evaluateMesh(snapshot, n)
            ConferenceTopologyMode.ANCHOR -> evaluateAnchor(snapshot, n)
        }
    }

    private fun evaluateMesh(
        snapshot: ConferenceTopologySnapshot,
        n: Int
    ): ConferenceCapacityEvaluation {
        if (n >= ANCHOR_THRESHOLD) {
            return ConferenceCapacityEvaluation.Rejected("MESH forbidden at N>=4")
        }
        if (snapshot.actualMediaEdges.isNotEmpty()) {
            return ConferenceCapacityEvaluation.Rejected("MESH ActualMediaEdgeSet must be empty")
        }
        return ConferenceCapacityEvaluation.MeshAdmitted
    }

    private fun evaluateAnchor(
        snapshot: ConferenceTopologySnapshot,
        n: Int
    ): ConferenceCapacityEvaluation {
        val anchor = snapshot.anchorId
            ?: return ConferenceCapacityEvaluation.Rejected("ANCHOR snapshot missing anchorId")
        val expected = expectedStarEdgeCount(n)
        if (snapshot.actualMediaEdges.size != expected) {
            return ConferenceCapacityEvaluation.Rejected("star edge count must be N-1")
        }
        val expectedEdges = ConferenceTopologyContract.anchorStarEdges(anchor, snapshot.members)
        if (snapshot.actualMediaEdges != expectedEdges) {
            return ConferenceCapacityEvaluation.Rejected("ActualMediaEdgeSet must be anchor-star, not peer-pair")
        }
        return ConferenceCapacityEvaluation.AnchorAdmitted(expected)
    }
}
