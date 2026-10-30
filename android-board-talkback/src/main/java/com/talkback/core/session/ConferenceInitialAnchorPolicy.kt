package com.talkback.core.session

/**
 * Conference CREATE-phase initial anchor candidate (Option B).
 *
 * ```text
 * CREATE (first MESH → ANCHOR admission):
 *   candidateAnchorId = initiatorModuleId
 *
 * RUN (preserve / failover / ordinary re-read):
 *   candidateAnchorId = rankingCandidateAnchorId (unchanged ranking path)
 * ```
 *
 * GROUP / PTT election is out of scope — callers must not invoke this for non-Conference admission.
 */
object ConferenceInitialAnchorPolicy {

    enum class Phase {
        CREATE,
        RUN
    }

    enum class Reason {
        INITIAL_ANCHOR_POLICY,
        RANKING_CANDIDATE
    }

    data class Input(
        val initiatorModuleId: String,
        val members: List<String>,
        val threshold: Int,
        val currentTopologyMode: ConferenceTopologyMode?,
        val currentAnchorId: String?,
        /** From [AnchorRanking.elect]; used only when not CREATE first admission. */
        val rankingCandidateAnchorId: String?
    )

    data class Output(
        val candidateAnchorId: String?,
        val phase: Phase,
        val reason: Reason
    )

    fun resolve(input: Input): Output {
        if (isCreateFirstAnchorAdmission(input)) {
            return Output(
                candidateAnchorId = input.initiatorModuleId,
                phase = Phase.CREATE,
                reason = Reason.INITIAL_ANCHOR_POLICY
            )
        }
        return Output(
            candidateAnchorId = input.rankingCandidateAnchorId,
            phase = Phase.RUN,
            reason = Reason.RANKING_CANDIDATE
        )
    }

    /**
     * First Conference anchor admission: roster at threshold, no established anchor yet.
     * Equivalent to MESH → ANCHOR CREATE transition input.
     */
    fun isCreateFirstAnchorAdmission(input: Input): Boolean {
        val members = input.members.distinct()
        if (members.size < input.threshold) return false
        if (input.currentTopologyMode == ConferenceTopologyMode.ANCHOR) return false
        if (input.currentAnchorId != null) return false
        return input.initiatorModuleId in members
    }
}
