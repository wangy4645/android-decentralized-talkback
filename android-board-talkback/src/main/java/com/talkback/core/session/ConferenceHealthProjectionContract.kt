package com.talkback.core.session

/**
 * Phase 2-5: ConferenceHealth as read-only aggregate projection.
 * Does not mutate topology, does not produce recovery targets, does not read Controller.
 * See docs/analysis/0056-phase-2-5-health-projection-contract.md
 */
data class MediaEdgeUsabilityObservation(
    val edge: MediaEdge,
    val usable: Boolean,
    val reconnecting: Boolean = false
)

data class RecoveryProgressFact(
    val inFlight: Boolean = false,
    val succeeded: Boolean = false,
    val failed: Boolean = false
)

data class ConferenceHealthProjection(
    val topologyMode: ConferenceTopologyMode,
    val anchorHealthModel: Boolean,
    val mediaUsable: Boolean,
    val recoveryInFlight: Boolean,
    val recoveryFailed: Boolean
)

data class ConferenceHealthProjectionInput(
    val snapshot: ConferenceTopologySnapshot,
    val mediaObservations: Set<MediaEdgeUsabilityObservation> = emptySet(),
    val recovery: RecoveryProgressFact = RecoveryProgressFact()
)

object ConferenceHealthProjectionContract {

    fun project(input: ConferenceHealthProjectionInput): ConferenceHealthProjection {
        val snapshot = input.snapshot
        val mesh = snapshot.topologyMode == ConferenceTopologyMode.MESH
        val usableOnAdmitted = if (mesh) {
            false
        } else {
            input.mediaObservations.any { obs ->
                obs.usable && obs.edge in snapshot.actualMediaEdges
            }
        }
        return ConferenceHealthProjection(
            topologyMode = snapshot.topologyMode,
            anchorHealthModel = snapshot.topologyMode == ConferenceTopologyMode.ANCHOR,
            mediaUsable = usableOnAdmitted,
            recoveryInFlight = input.recovery.inFlight,
            recoveryFailed = input.recovery.failed
        )
    }
}
