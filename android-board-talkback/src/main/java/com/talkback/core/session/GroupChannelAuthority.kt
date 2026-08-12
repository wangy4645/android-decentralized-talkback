package com.talkback.core.session

import com.talkback.core.model.ModuleId

/**
 * ADR-0053 — per-channel GROUP bootstrap authority (control plane).
 *
 * Decides authority validity, stale detection (E1/E2/E3), invalidation, and emission/ingress gates.
 * Does not own ICE/media teardown or signaling transport.
 */
class GroupChannelAuthority {

    private val channels = LinkedHashMap<String, ChannelRecord>()

    fun snapshot(channelId: String): GroupAuthoritySnapshot =
        channels[channelId]?.toSnapshot(channelId) ?: GroupAuthoritySnapshot.absent(channelId)

    /**
     * Recompute authority from a locally observable [observation] and persist channel state.
     */
    fun evaluate(channelId: String, observation: GroupAuthorityObservation): GroupAuthoritySnapshot {
        val record = recordFor(channelId)
        if (record.state == GroupAuthorityState.AUTHORITY_INVALIDATED) {
            return record.toSnapshot(channelId)
        }
        if (record.state == GroupAuthorityState.STALE_PRIMARY) {
            return record.toSnapshot(channelId)
        }

        val candidate = observation.bootstrapCandidate
        val isCandidate = candidate == observation.localModuleId
        val isAuthorityHolder = isCandidate

        applyObservationMetadata(record, observation, candidate)

        val staleEvidence = detectStaleEvidence(observation, isAuthorityHolder, record.hadEstablishedAuthority)
        if (staleEvidence != null) {
            record.state = GroupAuthorityState.STALE_PRIMARY
            record.evidence = staleEvidence
            return record.toSnapshot(channelId)
        }

        if (observation.localSessionId != null && observation.sessionIdentityValid) {
            record.state = GroupAuthorityState.VALID_PRIMARY
            record.evidence = null
            if (isAuthorityHolder) {
                record.hadEstablishedAuthority = true
            }
            return record.toSnapshot(channelId)
        }

        record.state = GroupAuthorityState.BOOTSTRAP_REQUIRED
        record.evidence = null
        return record.toSnapshot(channelId)
    }

    /**
     * Synchronous logical invalidation commit (I-AUTH-1/2/3).
     */
    fun commitAuthorityInvalidation(channelId: String): GroupAuthorityInvalidationResult {
        val record = recordFor(channelId)
        require(record.state == GroupAuthorityState.STALE_PRIMARY) {
            "commitAuthorityInvalidation requires STALE_PRIMARY, was ${record.state}"
        }
        val oldSessionId = record.sessionId
        val clearedPending = oldSessionId?.let { record.pendingJoinCountBySession.remove(it) } ?: 0
        record.sessionId = null
        record.state = GroupAuthorityState.AUTHORITY_INVALIDATED
        record.hadEstablishedAuthority = false
        record.evidence = record.evidence?.copy(invalidationCommitted = true)
        return GroupAuthorityInvalidationResult(
            channelId = channelId,
            oldSessionId = oldSessionId,
            clearedPendingJoinCount = clearedPending
        )
    }

    /**
     * After [commitAuthorityInvalidation], move to bootstrap evaluation when no valid authority remains.
     */
    fun advanceToBootstrapRequired(
        channelId: String,
        observation: GroupAuthorityObservation
    ): GroupAuthoritySnapshot {
        val record = recordFor(channelId)
        require(record.state == GroupAuthorityState.AUTHORITY_INVALIDATED) {
            "advanceToBootstrapRequired requires AUTHORITY_INVALIDATED, was ${record.state}"
        }
        applyObservationMetadata(record, observation, observation.bootstrapCandidate)
        record.state = GroupAuthorityState.BOOTSTRAP_REQUIRED
        record.sessionId = null
        return record.toSnapshot(channelId)
    }

    private fun applyObservationMetadata(
        record: ChannelRecord,
        observation: GroupAuthorityObservation,
        candidate: ModuleId
    ) {
        record.candidate = candidate
        record.sessionId = observation.localSessionId
        record.peerCandidateBootstrapEmission = observation.peerCandidateBootstrapEmission
    }

    fun markRecovered(
        channelId: String,
        localSessionId: String,
        candidate: ModuleId
    ): GroupAuthoritySnapshot {
        val record = recordFor(channelId)
        record.state = GroupAuthorityState.VALID_PRIMARY
        record.sessionId = localSessionId
        record.candidate = candidate
        record.evidence = null
        record.hadEstablishedAuthority = true
        return record.toSnapshot(channelId)
    }

    fun mayEmitBootstrap(snapshot: GroupAuthoritySnapshot, localModuleId: ModuleId): Boolean {
        if (snapshot.state != GroupAuthorityState.BOOTSTRAP_REQUIRED) return false
        if (snapshot.candidate != localModuleId) return false
        return !validAuthorityHeldAnywhere(snapshot, localModuleId)
    }

    fun mayPerformMaintenance(snapshot: GroupAuthoritySnapshot): Boolean =
        snapshot.state == GroupAuthorityState.VALID_PRIMARY

    fun decideJoinIngress(
        snapshot: GroupAuthoritySnapshot,
        joinSessionId: String
    ): GroupJoinIngressDecision = when (snapshot.state) {
        GroupAuthorityState.STALE_PRIMARY -> GroupJoinIngressDecision.STALE_AUTHORITY_EVIDENCE_ONLY
        GroupAuthorityState.AUTHORITY_INVALIDATED -> GroupJoinIngressDecision.STALE_AUTHORITY_REJECTED
        GroupAuthorityState.BOOTSTRAP_REQUIRED -> {
            if (snapshot.sessionId == null || joinSessionId != snapshot.sessionId) {
                GroupJoinIngressDecision.STALE_AUTHORITY_REJECTED
            } else {
                GroupJoinIngressDecision.ACCEPT_OR_QUEUE_NORMAL
            }
        }
        GroupAuthorityState.VALID_PRIMARY -> GroupJoinIngressDecision.ACCEPT_OR_QUEUE_NORMAL
    }

    /**
     * Coordinator calls after ingress decision. During STALE, records E3 posture only — no pending queue.
     */
    fun onJoinIngress(
        channelId: String,
        sessionId: String,
        peerModuleId: String,
        @Suppress("UNUSED_PARAMETER") queuedNoSession: Boolean
    ): GroupJoinIngressDecision {
        val snapshot = snapshot(channelId)
        val decision = decideJoinIngress(snapshot, sessionId)
        val record = recordFor(channelId)
        when (decision) {
            GroupJoinIngressDecision.STALE_AUTHORITY_EVIDENCE_ONLY -> {
                record.peerJoinIngressQueuedNoSession.add(peerModuleId)
            }
            GroupJoinIngressDecision.ACCEPT_OR_QUEUE_NORMAL -> {
                record.pendingJoinCountBySession[sessionId] =
                    (record.pendingJoinCountBySession[sessionId] ?: 0) + 1
            }
            GroupJoinIngressDecision.STALE_AUTHORITY_REJECTED -> Unit
        }
        return decision
    }

    fun pendingJoinCount(channelId: String, sessionId: String): Int =
        channels[channelId]?.pendingJoinCountBySession?.get(sessionId) ?: 0

    fun localSessionId(channelId: String): String? = channels[channelId]?.sessionId

    private fun detectStaleEvidence(
        observation: GroupAuthorityObservation,
        isAuthorityHolder: Boolean,
        hadEstablishedAuthority: Boolean
    ): GroupAuthorityEvidence? {
        if (observation.activeBootstrapEmission) {
            return null
        }

        if (isAuthorityHolder &&
            hadEstablishedAuthority &&
            (observation.localSessionId == null || observation.sessionTerminal)
        ) {
            return GroupAuthorityEvidence(
                kind = GroupAuthorityEvidenceKind.E1_LOCAL_AUTHORITY_MISSING,
                detail = "authority_holder_session_absent_or_terminal"
            )
        }

        if (observation.localSessionId != null &&
            isAuthorityHolder &&
            !observation.sessionIdentityValid
        ) {
            return GroupAuthorityEvidence(
                kind = GroupAuthorityEvidenceKind.E2_SESSION_IDENTITY_INVALID,
                detail = "session_identity_not_reconciled_with_topology"
            )
        }

        if (detectE3(observation, isAuthorityHolder)) {
            return GroupAuthorityEvidence(
                kind = GroupAuthorityEvidenceKind.E3_MEMBERSHIP_BOOTSTRAP_INCOMPATIBILITY,
                detail = "peer_bootstrap_posture_incompatible_with_join_maintenance"
            )
        }

        return null
    }

    private fun detectE3(
        observation: GroupAuthorityObservation,
        isAuthorityHolder: Boolean
    ): Boolean {
        if (!isAuthorityHolder) return false
        if (observation.localSessionId == null) return false
        if (observation.activeBootstrapEmission) return false
        if (!observation.joinOrientedMaintenanceActive) return false

        return observation.claimedRoster.any { peerId ->
            val posture = observation.peerBootstrapPosture[peerId] ?: return@any false
            posture.joinIngressQueuedNoSession ||
                posture.waitingForPrimaryObserved ||
                posture.rosterMeshIncompatible
        }
    }

    private fun validAuthorityHeldAnywhere(
        snapshot: GroupAuthoritySnapshot,
        localModuleId: ModuleId
    ): Boolean {
        if (snapshot.state == GroupAuthorityState.VALID_PRIMARY) return true
        val peerBooting = snapshot.peerCandidateBootstrapEmission
        if (peerBooting != null && peerBooting != localModuleId) return true
        return false
    }

    private fun recordFor(channelId: String): ChannelRecord =
        channels.getOrPut(channelId) { ChannelRecord() }

    private data class ChannelRecord(
        var state: GroupAuthorityState = GroupAuthorityState.BOOTSTRAP_REQUIRED,
        var sessionId: String? = null,
        var candidate: ModuleId? = null,
        var evidence: GroupAuthorityEvidence? = null,
        var hadEstablishedAuthority: Boolean = false,
        val pendingJoinCountBySession: MutableMap<String, Int> = LinkedHashMap(),
        val peerJoinIngressQueuedNoSession: MutableSet<String> = LinkedHashSet(),
        var peerCandidateBootstrapEmission: ModuleId? = null
    ) {
        fun toSnapshot(channelId: String): GroupAuthoritySnapshot = GroupAuthoritySnapshot(
            channelId = channelId,
            state = state,
            candidate = candidate,
            evidence = evidence,
            sessionId = sessionId,
            hadEstablishedAuthority = hadEstablishedAuthority,
            peerCandidateBootstrapEmission = peerCandidateBootstrapEmission
        )
    }
}

enum class GroupAuthorityState {
    VALID_PRIMARY,
    STALE_PRIMARY,
    AUTHORITY_INVALIDATED,
    BOOTSTRAP_REQUIRED
}

enum class GroupAuthorityEvidenceKind {
    E1_LOCAL_AUTHORITY_MISSING,
    E2_SESSION_IDENTITY_INVALID,
    E3_MEMBERSHIP_BOOTSTRAP_INCOMPATIBILITY
}

data class GroupAuthorityEvidence(
    val kind: GroupAuthorityEvidenceKind,
    val detail: String,
    val invalidationCommitted: Boolean = false
)

data class GroupAuthoritySnapshot(
    val channelId: String,
    val state: GroupAuthorityState,
    val candidate: ModuleId?,
    val evidence: GroupAuthorityEvidence?,
    val sessionId: String?,
    val hadEstablishedAuthority: Boolean,
    val peerCandidateBootstrapEmission: ModuleId? = null
) {
    val isStale: Boolean get() = state == GroupAuthorityState.STALE_PRIMARY

    companion object {
        fun absent(channelId: String): GroupAuthoritySnapshot = GroupAuthoritySnapshot(
            channelId = channelId,
            state = GroupAuthorityState.BOOTSTRAP_REQUIRED,
            candidate = null,
            evidence = null,
            sessionId = null,
            hadEstablishedAuthority = false
        )
    }
}

/**
 * Locally observable inputs for authority evaluation (ADR-0053).
 * [bootstrapCandidate] is typically [ChannelMeshHostElection.electHost].
 */
data class GroupAuthorityObservation(
    val localModuleId: ModuleId,
    val bootstrapCandidate: ModuleId,
    val dialableRemoteModuleIds: Collection<String>,
    val localSessionId: String?,
    val sessionTerminal: Boolean = false,
    val sessionIdentityValid: Boolean = true,
    val claimedRoster: Set<String> = emptySet(),
    val peerBootstrapPosture: Map<String, PeerBootstrapPostureEvidence> = emptyMap(),
    val activeBootstrapEmission: Boolean = false,
    val joinOrientedMaintenanceActive: Boolean = false,
    val peerCandidateBootstrapEmission: ModuleId? = null
) {
    companion object {
        fun withCandidate(
            localModuleId: ModuleId,
            dialableRemoteModuleIds: Collection<String>,
            healthByModule: Map<String, AnchorHealthSnapshot> = emptyMap(),
            block: Builder.() -> Unit
        ): GroupAuthorityObservation {
            val builder = Builder(localModuleId, dialableRemoteModuleIds, healthByModule)
            builder.block()
            return builder.build()
        }
    }

    class Builder(
        private val localModuleId: ModuleId,
        private val dialableRemoteModuleIds: Collection<String>,
        private val healthByModule: Map<String, AnchorHealthSnapshot>
    ) {
        var localSessionId: String? = null
        var sessionTerminal: Boolean = false
        var sessionIdentityValid: Boolean = true
        var claimedRoster: Set<String> = emptySet()
        var peerBootstrapPosture: Map<String, PeerBootstrapPostureEvidence> = emptyMap()
        var activeBootstrapEmission: Boolean = false
        var joinOrientedMaintenanceActive: Boolean = false
        var peerCandidateBootstrapEmission: ModuleId? = null

        fun build(): GroupAuthorityObservation {
            val candidate = ChannelMeshHostElection.electHost(
                localModuleId,
                dialableRemoteModuleIds,
                healthByModule
            )
            return GroupAuthorityObservation(
                localModuleId = localModuleId,
                bootstrapCandidate = candidate,
                dialableRemoteModuleIds = dialableRemoteModuleIds,
                localSessionId = localSessionId,
                sessionTerminal = sessionTerminal,
                sessionIdentityValid = sessionIdentityValid,
                claimedRoster = claimedRoster,
                peerBootstrapPosture = peerBootstrapPosture,
                activeBootstrapEmission = activeBootstrapEmission,
                joinOrientedMaintenanceActive = joinOrientedMaintenanceActive,
                peerCandidateBootstrapEmission = peerCandidateBootstrapEmission
            )
        }
    }
}

data class PeerBootstrapPostureEvidence(
    val joinIngressQueuedNoSession: Boolean = false,
    val waitingForPrimaryObserved: Boolean = false,
    /** Peer is in claimed roster but not admitted to local mesh membership. */
    val rosterMeshIncompatible: Boolean = false
)

data class GroupAuthorityInvalidationResult(
    val channelId: String,
    val oldSessionId: String?,
    val clearedPendingJoinCount: Int
)

enum class GroupJoinIngressDecision {
    ACCEPT_OR_QUEUE_NORMAL,
    STALE_AUTHORITY_EVIDENCE_ONLY,
    STALE_AUTHORITY_REJECTED
}
