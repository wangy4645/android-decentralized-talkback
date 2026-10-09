package com.talkback.core.session

/**
 * Maps topology + per-edge media facts into [ConferencePresenceSnapshot].
 * ANCHOR: VIA_ANCHOR requires that star edge's usability fact (not local ICE to anchor).
 * MESH / no topology: ICE adjacency only.
 */
data class CppAnchorPresenceMediaBuild(
    val mediaByModuleId: Map<String, CppMediaRelation>,
    val reconnectingModuleIds: Set<String>
)

data class CppPresenceObservation(
    val snapshot: ConferencePresenceSnapshot,
    val reconnectingModuleIds: Set<String>
)

object ConferencePresenceFactsAdapter {

    fun snapshotFromLocalObservation(
        conferenceId: String,
        producerModuleId: String,
        rosterEpoch: Long,
        anchorEpoch: Long,
        meshGeneration: Long,
        producedAtMs: Long,
        localModuleId: String,
        iceConnectedRemoteIds: Set<String>,
        mediaUnavailableRemoteIds: Set<String> = emptySet(),
        topology: ConferenceTopologySnapshot? = null,
        perEdgeFacts: List<PerEdgeMediaUsabilityFact> = emptyList(),
        nowMs: Long = producedAtMs
    ): ConferencePresenceSnapshot = projectFromObservation(
        conferenceId = conferenceId,
        producerModuleId = producerModuleId,
        rosterEpoch = rosterEpoch,
        anchorEpoch = anchorEpoch,
        meshGeneration = meshGeneration,
        producedAtMs = producedAtMs,
        localModuleId = localModuleId,
        iceConnectedRemoteIds = iceConnectedRemoteIds,
        mediaUnavailableRemoteIds = mediaUnavailableRemoteIds,
        topology = topology,
        perEdgeFacts = perEdgeFacts,
        nowMs = nowMs
    ).snapshot

    fun projectFromObservation(
        conferenceId: String,
        producerModuleId: String,
        rosterEpoch: Long,
        anchorEpoch: Long,
        meshGeneration: Long,
        producedAtMs: Long,
        localModuleId: String,
        iceConnectedRemoteIds: Set<String>,
        mediaUnavailableRemoteIds: Set<String> = emptySet(),
        topology: ConferenceTopologySnapshot? = null,
        perEdgeFacts: List<PerEdgeMediaUsabilityFact> = emptyList(),
        nowMs: Long = producedAtMs
    ): CppPresenceObservation {
        val build = if (
            topology != null &&
            topology.topologyMode == ConferenceTopologyMode.ANCHOR &&
            topology.anchorId != null
        ) {
            buildAnchorPresenceMedia(
                topology = topology,
                producerModuleId = producerModuleId,
                localModuleId = localModuleId,
                iceConnectedRemoteIds = iceConnectedRemoteIds,
                mediaUnavailableRemoteIds = mediaUnavailableRemoteIds,
                perEdgeFacts = perEdgeFacts,
                nowMs = nowMs
            )
        } else {
            CppAnchorPresenceMediaBuild(
                mediaByModuleId = iceAdjacencyMedia(
                    producerModuleId = producerModuleId,
                    localModuleId = localModuleId,
                    iceConnectedRemoteIds = iceConnectedRemoteIds,
                    mediaUnavailableRemoteIds = mediaUnavailableRemoteIds
                ),
                reconnectingModuleIds = emptySet()
            )
        }
        return CppPresenceObservation(
            snapshot = ConferencePresenceSnapshot(
                conferenceId = conferenceId,
                producerModuleId = producerModuleId,
                rosterEpoch = rosterEpoch,
                anchorEpoch = anchorEpoch,
                meshGeneration = meshGeneration,
                producedAtMs = producedAtMs,
                mediaByModuleId = build.mediaByModuleId
            ),
            reconnectingModuleIds = build.reconnectingModuleIds
        )
    }

    fun buildAnchorPresenceMedia(
        topology: ConferenceTopologySnapshot,
        producerModuleId: String,
        localModuleId: String,
        iceConnectedRemoteIds: Set<String>,
        mediaUnavailableRemoteIds: Set<String> = emptySet(),
        perEdgeFacts: List<PerEdgeMediaUsabilityFact> = emptyList(),
        nowMs: Long
    ): CppAnchorPresenceMediaBuild {
        val localFacts = incidentFactsFromLocalIce(
            topology = topology,
            localModuleId = localModuleId,
            iceConnectedRemoteIds = iceConnectedRemoteIds,
            producedAtMs = nowMs
        )
        val facts = localFacts + perEdgeFacts
        val input = ConferencePerEdgeMediaFactInput(
            snapshot = topology,
            localModuleId = localModuleId,
            facts = facts,
            nowMs = nowMs,
            localIceToAnchor = topology.anchorId in iceConnectedRemoteIds ||
                localModuleId == topology.anchorId
        )
        val media = LinkedHashMap<String, CppMediaRelation>()
        media[localModuleId] = if (producerModuleId == localModuleId) {
            CppMediaRelation.VIA_ANCHOR
        } else {
            CppMediaRelation.DIRECT
        }
        val reconnecting = LinkedHashSet<String>()
        for (id in topology.members) {
            if (id == localModuleId) continue
            val view = ConferencePerEdgeMediaFactContract.viewOf(input, id) ?: continue
            media[id] = view.mediaRelation
            if (view.reconnecting) reconnecting += id
        }
        for (id in mediaUnavailableRemoteIds) {
            if (id != localModuleId && id !in media) {
                media[id] = CppMediaRelation.DEGRADED
            }
        }
        return CppAnchorPresenceMediaBuild(media, reconnecting)
    }

    fun incidentFactsFromLocalIce(
        topology: ConferenceTopologySnapshot,
        localModuleId: String,
        iceConnectedRemoteIds: Set<String>,
        producedAtMs: Long
    ): List<PerEdgeMediaUsabilityFact> {
        if (topology.topologyMode != ConferenceTopologyMode.ANCHOR) return emptyList()
        val out = ArrayList<PerEdgeMediaUsabilityFact>()
        for (edge in topology.actualMediaEdges) {
            val remote = when (localModuleId) {
                edge.anchorModuleId -> edge.remoteModuleId
                edge.remoteModuleId -> edge.anchorModuleId
                else -> continue
            }
            out += PerEdgeMediaUsabilityFact(
                conferenceId = topology.conferenceId,
                anchorEpoch = topology.anchorEpoch,
                meshGeneration = topology.meshGeneration,
                producedAtMs = producedAtMs,
                producerModuleId = localModuleId,
                edge = edge,
                usable = remote in iceConnectedRemoteIds
            )
        }
        return out
    }

    private fun iceAdjacencyMedia(
        producerModuleId: String,
        localModuleId: String,
        iceConnectedRemoteIds: Set<String>,
        mediaUnavailableRemoteIds: Set<String>
    ): LinkedHashMap<String, CppMediaRelation> {
        val media = LinkedHashMap<String, CppMediaRelation>()
        media[localModuleId] = if (producerModuleId == localModuleId) {
            CppMediaRelation.VIA_ANCHOR
        } else {
            CppMediaRelation.DIRECT
        }
        for (id in iceConnectedRemoteIds) {
            if (id != localModuleId) {
                media[id] = CppMediaRelation.DIRECT
            }
        }
        for (id in mediaUnavailableRemoteIds) {
            if (id != localModuleId && id !in iceConnectedRemoteIds) {
                media[id] = CppMediaRelation.DEGRADED
            }
        }
        return media
    }
}
