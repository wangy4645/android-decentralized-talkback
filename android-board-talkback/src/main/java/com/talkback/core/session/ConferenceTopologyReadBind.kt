package com.talkback.core.session

/**
 * Phase 1b-2 / 1b-6: immutable topology snapshot → downstream read inputs.
 * All ANCHOR binds route through [ConferenceTopologyConsistencyContract.bindDownstreamView].
 * Consumers MUST NOT mutate [ConferenceTopologySnapshot]; bind copies fields only.
 */
object ConferenceTopologyReadBind {

    data class PresenceReadInput(
        val conferenceId: String,
        val rosterEpoch: Long,
        val anchorEpoch: Long,
        val meshGeneration: Long,
        val producerModuleId: String,
        val canonicalRoster: List<String>,
        val hostModuleId: String,
        val anchorId: String
    )

    fun bindDownstreamView(
        snapshot: ConferenceTopologySnapshot
    ): ConferenceTopologyConsistencyContract.BindResult =
        ConferenceTopologyConsistencyContract.bindDownstreamView(snapshot)

    fun tryPresenceReadInput(snapshot: ConferenceTopologySnapshot): PresenceReadInput? {
        return when (val bind = bindDownstreamView(snapshot)) {
            is ConferenceTopologyConsistencyContract.BindResult.Rejected -> null
            is ConferenceTopologyConsistencyContract.BindResult.Ok -> {
                if (bind.view.presenceReadPath != ConferencePresenceReadPath.ANCHOR_AUTHORITY_SNAPSHOT) {
                    null
                } else {
                    presenceReadInputFromBoundView(snapshot, bind.view)
                }
            }
        }
    }

    fun presenceReadInput(snapshot: ConferenceTopologySnapshot): PresenceReadInput {
        val bind = bindDownstreamView(snapshot)
        if (bind is ConferenceTopologyConsistencyContract.BindResult.Rejected) {
            error("topology downstream bind rejected: ${bind.reason}")
        }
        return presenceReadInputFromBoundView(snapshot, (bind as ConferenceTopologyConsistencyContract.BindResult.Ok).view)
    }

    private fun presenceReadInputFromBoundView(
        snapshot: ConferenceTopologySnapshot,
        view: ConferenceTopologyConsistencyContract.DownstreamTopologyView
    ): PresenceReadInput {
        require(view.presenceReadPath == ConferencePresenceReadPath.ANCHOR_AUTHORITY_SNAPSHOT) {
            "presenceReadInput requires ANCHOR authority snapshot path"
        }
        val anchorId = view.anchorId
            ?: error("ANCHOR topology snapshot requires anchorId")
        return PresenceReadInput(
            conferenceId = view.conferenceId,
            rosterEpoch = view.rosterEpoch,
            anchorEpoch = view.anchorEpoch,
            meshGeneration = view.meshGeneration,
            producerModuleId = anchorId,
            canonicalRoster = view.members,
            hostModuleId = snapshot.hostModuleId,
            anchorId = anchorId
        )
    }
}
