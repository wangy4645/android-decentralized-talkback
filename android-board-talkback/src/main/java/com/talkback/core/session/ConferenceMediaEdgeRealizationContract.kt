package com.talkback.core.session

/**
 * ADR-0056 runtime conformance: Desired MediaEdge → who may createOffer / PC.
 *
 * Does not introduce RealizedMediaEdgeSet. Does not change Ready / Health / Recovery.
 *
 * MESH: pairwise invite remains host/offerer-local (no star Desired set).
 * ANCHOR: only the Anchor endpoint of an admitted **current-generation** edge may createOffer;
 *         a conference PC may exist only for an incident admitted edge.
 * ANCHOR + missing snapshot: NOT_AUTHORIZED (no Host-centric pairwise fallback).
 */
enum class ConferenceMediaOfferEntry {
    INITIAL_INVITE,
    RESEND,
    REJOIN,
    COUNTER_INVITE,
    MESH_JOIN,
    RECOVERY_REATTACH
}

data class RealizationAuthRequest(
    val conferenceId: String,
    val localModuleId: String,
    val remoteModuleId: String,
    val meshGeneration: Long,
    val anchorEpoch: Long,
    val declaredMode: ConferenceTopologyMode
)

sealed class RealizationDecision {
    data object Authorized : RealizationDecision()
    data class Denied(val reason: String) : RealizationDecision()
}

object ConferenceMediaEdgeRealizationContract {

    const val REASON_SNAPSHOT_REQUIRED = "SNAPSHOT_REQUIRED"
    const val REASON_CONFERENCE_MISMATCH = "CONFERENCE_MISMATCH"
    const val REASON_GENERATION_STALE = "GENERATION_STALE"
    const val REASON_EPOCH_STALE = "EPOCH_STALE"
    const val REASON_NOT_EDGE_OWNER = "NOT_EDGE_OWNER"
    const val REASON_EDGE_NOT_ADMITTED = "EDGE_NOT_ADMITTED"
    const val REASON_SELF = "SELF"

    fun admittedIncidentEdge(
        localModuleId: String,
        remoteModuleId: String,
        snapshot: ConferenceTopologySnapshot
    ): MediaEdge? {
        if (snapshot.topologyMode != ConferenceTopologyMode.ANCHOR) return null
        if (localModuleId.isBlank() || remoteModuleId.isBlank()) return null
        if (localModuleId == remoteModuleId) return null
        val anchor = snapshot.anchorId ?: return null
        val edge = when {
            localModuleId == anchor -> MediaEdge(anchor, remoteModuleId)
            remoteModuleId == anchor -> MediaEdge(anchor, localModuleId)
            else -> null
        } ?: return null
        return edge.takeIf { it in snapshot.actualMediaEdges }
    }

    fun authorizeCreateOffer(
        request: RealizationAuthRequest,
        current: ConferenceTopologySnapshot?
    ): RealizationDecision = authorize(request, current, requireAnchorOwner = true)

    fun authorizeCreatePeerConnection(
        request: RealizationAuthRequest,
        current: ConferenceTopologySnapshot?
    ): RealizationDecision = authorize(request, current, requireAnchorOwner = false)

    /**
     * Control-plane membership invite is not media realization.
     * Host≠Anchor may still membership-invite spokes.
     */
    fun maySendMembershipInvite(localModuleId: String, remoteModuleId: String): Boolean =
        localModuleId != remoteModuleId && localModuleId.isNotBlank() && remoteModuleId.isNotBlank()

    /**
     * Every Host-centric media entry uses the same offer gate.
     * MESH: legacy pairwise. ANCHOR: current Desired star owner only.
     */
    fun mayCreateOfferAtEntry(
        @Suppress("UNUSED_PARAMETER") entry: ConferenceMediaOfferEntry,
        request: RealizationAuthRequest,
        current: ConferenceTopologySnapshot?
    ): Boolean = authorizeCreateOffer(request, current) is RealizationDecision.Authorized

    fun mayCreateOffer(
        localModuleId: String,
        remoteModuleId: String,
        snapshot: ConferenceTopologySnapshot?,
        declaredMode: ConferenceTopologyMode = snapshot?.topologyMode ?: ConferenceTopologyMode.MESH,
        conferenceId: String = snapshot?.conferenceId.orEmpty(),
        meshGeneration: Long = snapshot?.meshGeneration ?: 0L,
        anchorEpoch: Long = snapshot?.anchorEpoch ?: 0L
    ): Boolean = authorizeCreateOffer(
        RealizationAuthRequest(
            conferenceId = conferenceId,
            localModuleId = localModuleId,
            remoteModuleId = remoteModuleId,
            meshGeneration = meshGeneration,
            anchorEpoch = anchorEpoch,
            declaredMode = declaredMode
        ),
        snapshot
    ) is RealizationDecision.Authorized

    fun mayCreateConferencePeerConnection(
        localModuleId: String,
        remoteModuleId: String,
        snapshot: ConferenceTopologySnapshot?,
        declaredMode: ConferenceTopologyMode = snapshot?.topologyMode ?: ConferenceTopologyMode.MESH,
        conferenceId: String = snapshot?.conferenceId.orEmpty(),
        meshGeneration: Long = snapshot?.meshGeneration ?: 0L,
        anchorEpoch: Long = snapshot?.anchorEpoch ?: 0L
    ): Boolean = authorizeCreatePeerConnection(
        RealizationAuthRequest(
            conferenceId = conferenceId,
            localModuleId = localModuleId,
            remoteModuleId = remoteModuleId,
            meshGeneration = meshGeneration,
            anchorEpoch = anchorEpoch,
            declaredMode = declaredMode
        ),
        snapshot
    ) is RealizationDecision.Authorized

    fun offerRemotesIfAnchor(
        localModuleId: String,
        snapshot: ConferenceTopologySnapshot
    ): Set<String> {
        if (snapshot.topologyMode != ConferenceTopologyMode.ANCHOR) return emptySet()
        if (snapshot.anchorId != localModuleId) return emptySet()
        return snapshot.actualMediaEdges.map { it.remoteModuleId }.toSet()
    }

    /**
     * Same-session Anchor SDP: current-generation admitted spoke may apply offer.
     * Old Host-spoke cannot regain realization authority.
     */
    fun shouldAcceptAnchorOfferOnExistingSession(
        existingSessionId: String,
        inviteSessionId: String,
        localModuleId: String,
        callerModuleId: String,
        payloadSdpBlank: Boolean,
        snapshot: ConferenceTopologySnapshot?,
        declaredMode: ConferenceTopologyMode = snapshot?.topologyMode ?: ConferenceTopologyMode.MESH
    ): Boolean {
        if (existingSessionId != inviteSessionId) return false
        if (payloadSdpBlank) return false
        return mayCreateConferencePeerConnection(
            localModuleId,
            callerModuleId,
            snapshot,
            declaredMode
        ) && !mayCreateOffer(localModuleId, callerModuleId, snapshot, declaredMode)
    }

    private fun authorize(
        request: RealizationAuthRequest,
        current: ConferenceTopologySnapshot?,
        requireAnchorOwner: Boolean
    ): RealizationDecision {
        if (request.localModuleId == request.remoteModuleId) {
            return RealizationDecision.Denied(REASON_SELF)
        }
        if (request.declaredMode == ConferenceTopologyMode.MESH) {
            return RealizationDecision.Authorized
        }
        if (current == null || current.topologyMode != ConferenceTopologyMode.ANCHOR) {
            return RealizationDecision.Denied(REASON_SNAPSHOT_REQUIRED)
        }
        if (request.conferenceId != current.conferenceId) {
            return RealizationDecision.Denied(REASON_CONFERENCE_MISMATCH)
        }
        if (request.meshGeneration != current.meshGeneration) {
            return RealizationDecision.Denied(REASON_GENERATION_STALE)
        }
        if (request.anchorEpoch != current.anchorEpoch) {
            return RealizationDecision.Denied(REASON_EPOCH_STALE)
        }
        val edge = admittedIncidentEdge(request.localModuleId, request.remoteModuleId, current)
            ?: return RealizationDecision.Denied(REASON_EDGE_NOT_ADMITTED)
        if (requireAnchorOwner && request.localModuleId != edge.anchorModuleId) {
            return RealizationDecision.Denied(REASON_NOT_EDGE_OWNER)
        }
        return RealizationDecision.Authorized
    }
}
