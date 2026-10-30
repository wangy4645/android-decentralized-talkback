package com.talkback.core.session

/**
 * Pure Conference presence projection (ADR-0022 R27′-B).
 * Sibling to [ConferenceRuntimeProjector]; consumes the same read-only facts, different semantics.
 * No side effects.
 */
object ConferencePresenceProjector {

    const val DEFAULT_STALE_AFTER_MS = 5_000L

    data class Input(
        val sessionAccepted: Boolean,
        /** Primary meeting size from membership (ADR-0020 P2). */
        val joinedParticipantCount: Int,
        /** ICE-direct connected remote module ids (connectivity fact). */
        val connectedRemoteModuleIds: Set<String>,
        /** Per-edge recovery facts from [ConferenceEdgeRecoveryController]. */
        val recoveringRemoteModuleIds: Set<String> = emptySet(),
        /** Advisory media-health facts; MUST NOT affect [joinedCount]. */
        val mediaUnavailableRemoteModuleIds: Set<String> = emptySet()
    )

    fun project(input: Input): ConferencePresenceProjection {
        if (!input.sessionAccepted) {
            return ConferencePresenceProjection(
                joinedCount = 0,
                connectedCount = 0,
                recoveringPeers = emptySet(),
                mediaUnavailablePeers = emptySet()
            )
        }
        val connectedCount = 1 + input.connectedRemoteModuleIds.size
        return ConferencePresenceProjection(
            joinedCount = input.joinedParticipantCount,
            connectedCount = connectedCount,
            recoveringPeers = input.recoveringRemoteModuleIds.toSet(),
            mediaUnavailablePeers = input.mediaUnavailableRemoteModuleIds.toSet()
        )
    }

    /**
     * CPP compose: roster wins the list; snapshot supplies media only.
     * Rejects stale producer (anchorEpoch &lt; current). Does not equality-gate meshGeneration.
     */
    fun compose(
        conferenceId: String,
        canonicalRoster: List<String>,
        currentAnchorEpoch: Long,
        snapshot: ConferencePresenceSnapshot?,
        nowMs: Long,
        staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
        recoveringModuleIds: Set<String> = emptySet()
    ): ParticipantPresenceProjection {
        val accepted = snapshot?.takeIf { snap ->
            snap.conferenceId == conferenceId && snap.anchorEpoch == currentAnchorEpoch
        }
        val snapshotStale = accepted != null && nowMs - accepted.producedAtMs > staleAfterMs
        val records = canonicalRoster.distinct().map { moduleId ->
            val media = accepted?.mediaByModuleId?.get(moduleId)
            val evidence = when {
                accepted == null || media == null -> CppEvidence.UNKNOWN
                snapshotStale -> CppEvidence.STALE
                else -> CppEvidence.FRESH
            }
            ParticipantPresenceRecord(
                moduleId = moduleId,
                membership = CppMembership.JOINED,
                mediaRelation = media ?: CppMediaRelation.NONE,
                evidence = evidence
            )
        }
        val recovering = recoveringModuleIds.intersect(canonicalRoster.toSet())
        return ParticipantPresenceProjection(participants = records, recoveringPeers = recovering)
    }

    fun selectPresenceSnapshot(
        conferenceId: String,
        currentAnchorEpoch: Long,
        candidates: List<ConferencePresenceSnapshot>
    ): ConferencePresenceSnapshot? =
        candidates
            .filter { it.conferenceId == conferenceId && it.anchorEpoch == currentAnchorEpoch }
            .maxWithOrNull(
                compareBy<ConferencePresenceSnapshot> { it.meshGeneration }
                    .thenBy { it.producedAtMs }
            )
}
