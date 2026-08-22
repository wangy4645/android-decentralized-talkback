package com.talkback.core.session.failure

/**
 * Phase A PR-A3 audit event kinds — ordered stages in the failure telemetry chain.
 */
enum class ConferenceFailureTelemetryStage {
    /** Runtime input recorded (symptoms / holder attribution). Not a terminal. */
    CAUSE_INPUT_RECORDED,
    /** Classified L1 EDGE_FAILED on cause edge. */
    L1_CLASSIFIED,
    /** Classified L2 DOMAIN_BLOCKED on impact edge. */
    L2_CLASSIFIED,
    /** L-participant projection emitted. */
    PROJECTED,
}

data class ConferenceFailureTelemetryEvent(
    val stage: ConferenceFailureTelemetryStage,
    val edgeKey: String,
    val observedAtMs: Long,
    val generationScope: ConferenceFailureGenerationScope,
    val runtimeDomainRef: String,
    /** Frozen causeFact when applicable — never [LeaseBusyInput]. */
    val causeFact: ConferenceFailureCauseFact? = null,
    val causeEdgeKey: String? = null,
    /** B2-1 LEASE_BUSY path input only — audit visibility, not L2 causeFact. */
    val leaseBusyInputObserved: Boolean = false,
    val holderEdgeKey: String? = null,
    val terminal: ConferenceFailureTerminal? = null,
    val projection: ConferenceFailureParticipantProjection? = null,
) {
    init {
        if (stage == ConferenceFailureTelemetryStage.L2_CLASSIFIED) {
            require(terminal is ConferenceFailureTerminal.DomainBlocked) {
                "L2_CLASSIFIED requires DOMAIN_BLOCKED terminal"
            }
            require(!causeEdgeKey.isNullOrBlank()) { "L2 requires causeEdgeKey" }
            require(causeFact != null) { "L2 requires causeFact — not LEASE_BUSY alone" }
            require(runtimeDomainRef.isNotBlank()) { "L2 requires runtimeDomainRef" }
        }
    }
}

/** Marker: lease busy is input observation only, never serialized as causeFact. */
object LeaseBusyInput
