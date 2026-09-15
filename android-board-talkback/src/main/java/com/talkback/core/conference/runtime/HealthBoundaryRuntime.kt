package com.talkback.core.conference.runtime

/**
 * Q8 Health boundary facade: evidence aggregation + optional frozen Q12 projection.
 * Does not own Health semantics or authority (E2b-06).
 */
class HealthBoundaryRuntime(
    val selection: ConferenceMediaSelectionRuntime = ConferenceMediaSelectionRuntime(),
    val aggregator: RuntimeEvidenceAggregator = RuntimeEvidenceAggregator(),
    val projectionSeam: InjectableQ12ProjectionSeam = InjectableQ12ProjectionSeam(),
) {
    fun install(source: AdmittedMediaSource) = selection.install(source)

    fun hardFence(sourceIdentity: String, incarnationId: Long) =
        selection.hardFence(sourceIdentity, incarnationId)

    fun observeVoice(o: VoiceLevelObservation) = selection.observeVoice(o)

    fun selectTopK(nowMs: Long) = selection.selectTopK(nowMs)

    fun noteEvidence(kind: RuntimeDegradationKind, detail: String, atMs: Long) {
        aggregator.record(RuntimeDegradationEvidence(kind, detail, atMs))
    }

    fun noteLateForPlayout(atMs: Long) {
        aggregator.record(
            RuntimeDegradationEvidence(
                kind = RuntimeDegradationKind.LATE_FOR_PLAYOUT,
                detail = "single LATE_FOR_PLAYOUT",
                atMs = atMs,
            ),
        )
    }

    fun notePlcExhaustion(atMs: Long) {
        aggregator.record(
            RuntimeDegradationEvidence(
                kind = RuntimeDegradationKind.PLC_EXHAUSTION,
                detail = "MaxConsecutivePlcFrames exhausted",
                atMs = atMs,
            ),
        )
    }

    fun activeEvidence(nowMs: Long): List<RuntimeDegradationEvidence> =
        aggregator.activeAt(nowMs)

    fun projectionHints(nowMs: Long): List<String> =
        projectionSeam.project(aggregator.activeAt(nowMs))

    /**
     * P03-local inference gate used by harness: always empty when no frozen mapping.
     * Never invents IMPAIRED / UNAVAILABLE / NO_REMOTE_SOURCE from evidence alone.
     */
    fun inferredHealthLabelsWithoutMapping(@Suppress("UNUSED_PARAMETER") nowMs: Long): Set<String> {
        // Even with mappings, P03 does not own resulting Health — only forwards hints.
        return emptySet()
    }

    fun clearEvidenceMatchingDetail(detail: String) {
        aggregator.clearWhere { it.detail == detail }
    }

    fun authorityUnchangedSnapshot(): AuthoritySnapshot {
        val installed = selection.registry.installedSnapshot()
        return AuthoritySnapshot(
            admittedIdentities = installed.keys.toSet(),
            incarnationByIdentity = installed.mapValues { it.value.source.incarnationId },
            fenceByIdentity = installed.mapValues { it.value.fence },
            membershipIdentity = "MEM-FIXED",
            conferenceGeneration = 1L,
            anchorEpoch = 1L,
        )
    }
}
