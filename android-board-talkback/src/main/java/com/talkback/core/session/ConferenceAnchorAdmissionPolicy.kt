package com.talkback.core.session

/**
 * Phase 1b-3: pure anchor admission **decision** (topology mode / anchor id).
 *
 * Policy chain:
 * ```text
 * Roster → threshold (policy input) → AnchorAdmissionDecision → (later) Authority → Snapshot
 * ```
 *
 * **Not in scope:** [MediaEdge] / ActualMediaEdgeSet, ICE, Recovery, Bus.
 * **Threshold is policy input only** — never written to [ConferenceTopologySnapshot].
 */
data class AnchorAdmissionDecisionInput(
    val conferenceId: String,
    val members: List<String>,
    val hostModuleId: String,
    /** Election candidate; not overridden by [hostModuleId]. */
    val candidateAnchorId: String?,
    /** Policy input only — must not appear on topology snapshot. */
    val threshold: Int,
    val currentTopologyMode: ConferenceTopologyMode? = null,
    val currentAnchorId: String? = null,
    val rosterEpoch: Long = GroupMembershipSupport.INITIAL_ROSTER_EPOCH,
    val anchorEpoch: Long = 0L,
    val meshGeneration: Long = 0L
)

sealed class AnchorAdmissionDecision {
    /** Member count below threshold — stay on mesh topology. */
    data object Mesh : AnchorAdmissionDecision()

    /** Admit anchor-star topology centered on [anchorId]. */
    data class Anchor(val anchorId: String) : AnchorAdmissionDecision()

    data class Rejected(val reason: String) : AnchorAdmissionDecision()
}

object ConferenceAnchorAdmissionPolicy {

    fun decide(input: AnchorAdmissionDecisionInput): AnchorAdmissionDecision {
        require(input.threshold > 0) { "threshold must be positive" }
        val members = input.members.distinct()
        if (members.isEmpty()) {
            return AnchorAdmissionDecision.Rejected("empty members")
        }
        if (input.hostModuleId !in members) {
            return AnchorAdmissionDecision.Rejected("host not in members")
        }
        if (members.size < input.threshold) {
            return AnchorAdmissionDecision.Mesh
        }
        val preserved = preservedAnchor(input, members)
        if (preserved != null) {
            return AnchorAdmissionDecision.Anchor(preserved)
        }
        val candidate = input.candidateAnchorId
            ?: return AnchorAdmissionDecision.Rejected("missing candidate anchor")
        if (candidate !in members) {
            return AnchorAdmissionDecision.Rejected("candidate anchor not in members")
        }
        return AnchorAdmissionDecision.Anchor(candidate)
    }

    /**
     * When already on ANCHOR with a live anchor still in roster, keep it across ordinary reads.
     * Failover / explicit re-election must clear [currentAnchorId] before calling [decide].
     */
    private fun preservedAnchor(
        input: AnchorAdmissionDecisionInput,
        members: List<String>
    ): String? {
        if (input.currentTopologyMode != ConferenceTopologyMode.ANCHOR) return null
        val anchor = input.currentAnchorId ?: return null
        return anchor.takeIf { it in members }
    }

    /** Maps an admitted anchor decision to compose input. Threshold is intentionally omitted. */
    fun toAdmissionInput(
        input: AnchorAdmissionDecisionInput,
        decision: AnchorAdmissionDecision.Anchor
    ): AnchorAdmissionInput {
        require(decision.anchorId in input.members.distinct()) {
            "anchor must be roster member"
        }
        return AnchorAdmissionInput(
            conferenceId = input.conferenceId,
            hostModuleId = input.hostModuleId,
            anchorId = decision.anchorId,
            members = input.members.distinct(),
            rosterEpoch = input.rosterEpoch,
            anchorEpoch = input.anchorEpoch.coerceAtLeast(AnchorRanking.INITIAL_ANCHOR_EPOCH),
            meshGeneration = input.meshGeneration
        )
    }
}
