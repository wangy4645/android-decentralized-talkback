package com.talkback.core.conference.session.integration.cutover

/**
 * RC1 audible ownership state machine.
 * Enforces: at most one production audible owner (Anchor WebRTC vs multicast AudioTrack).
 */
class AudibleOwnershipController(
    private val anchorPort: AnchorAudiblePort,
    private val multicastPort: MulticastAudiblePort,
    private val shadowSessionActive: (String) -> Boolean,
    private val multicastAudibleEnabled: () -> Boolean,
) {
    @Volatile
    var state: AudibleOwnershipState = AudibleOwnershipState.ANCHOR_ACTIVE
        private set

    @Volatile
    var activeSessionId: String? = null
        private set

    fun armCutover(sessionId: String): CutoverOutcome {
        if (!multicastAudibleEnabled()) {
            return CutoverOutcome.REJECTED_PILOT_DISABLED
        }
        if (!shadowSessionActive(sessionId)) {
            return CutoverOutcome.REJECTED_PREREQUISITE
        }
        if (state != AudibleOwnershipState.ANCHOR_ACTIVE) {
            return CutoverOutcome.REJECTED_INVALID_STATE
        }
        state = AudibleOwnershipState.CUTOVER_ARMED
        activeSessionId = sessionId
        ReplacementCutoverObservability.logState(sessionId, state, "armed")
        return CutoverOutcome.APPLIED
    }

    fun executeCutover(sessionId: String): CutoverOutcome {
        if (!multicastAudibleEnabled()) {
            return CutoverOutcome.REJECTED_PILOT_DISABLED
        }
        if (state != AudibleOwnershipState.CUTOVER_ARMED || activeSessionId != sessionId) {
            return CutoverOutcome.REJECTED_INVALID_STATE
        }
        if (!shadowSessionActive(sessionId)) {
            return CutoverOutcome.REJECTED_PREREQUISITE
        }

        multicastPort.fenceProductionPlayout(sessionId)
        ReplacementCutoverObservability.logHandoff(
            sessionId,
            "CUTOVER_FENCE_MULTICAST",
            CutoverOutcome.APPLIED,
        )

        if (!anchorPort.releaseAnchorOwnership(sessionId)) {
            rollbackInternal(sessionId, RollbackTrigger.OWNERSHIP_INVARIANT_VIOLATION)
            return CutoverOutcome.REJECTED_HANDOFF_FAILURE
        }
        ReplacementCutoverObservability.logHandoff(
            sessionId,
            "CUTOVER_RELEASE_ANCHOR",
            CutoverOutcome.APPLIED,
        )

        if (!multicastPort.acquireProductionAudioTrack(sessionId)) {
            anchorPort.acquireAnchorOwnership(sessionId)
            state = AudibleOwnershipState.ANCHOR_ACTIVE
            ReplacementCutoverObservability.logRc1Field(
                "CUTOVER_FAILED",
                sessionId,
                "production_audiotrack_acquire_failure",
            )
            ReplacementCutoverObservability.logRc1Field(
                "AUTO_ROLLBACK_SUCCEEDED",
                sessionId,
                "after_acquire_failure",
            )
            ReplacementCutoverObservability.logRollback(
                sessionId,
                RollbackTrigger.PRODUCTION_AUDIOTRACK_ACQUIRE_FAILURE,
                CutoverOutcome.REJECTED_HANDOFF_FAILURE,
            )
            probeOwnership(sessionId)
            return CutoverOutcome.REJECTED_HANDOFF_FAILURE
        }
        ReplacementCutoverObservability.logHandoff(
            sessionId,
            "CUTOVER_ACQUIRE_MULTICAST",
            CutoverOutcome.APPLIED,
        )

        state = AudibleOwnershipState.MULTICAST_ACTIVE
        val verify = verifySingleOwner(sessionId)
        if (!verify) {
            ReplacementCutoverObservability.logRc1Field(
                "CUTOVER_FAILED",
                sessionId,
                "ownership_invariant_violation",
            )
            rollback(sessionId, RollbackTrigger.OWNERSHIP_INVARIANT_VIOLATION)
            ReplacementCutoverObservability.logRc1Field(
                "AUTO_ROLLBACK_SUCCEEDED",
                sessionId,
                "after_invariant_violation",
            )
            return CutoverOutcome.REJECTED_INVARIANT_VIOLATION
        }

        ReplacementCutoverObservability.logState(sessionId, state)
        ReplacementCutoverObservability.logRc1Field("CUTOVER_SUCCEEDED", sessionId)
        probeOwnership(sessionId)
        return CutoverOutcome.APPLIED
    }

    fun rollback(
        sessionId: String,
        trigger: RollbackTrigger,
    ): CutoverOutcome = rollbackInternal(sessionId, trigger)

    fun onReplacementRuntimeFatal(sessionId: String) {
        if (state == AudibleOwnershipState.MULTICAST_ACTIVE && activeSessionId == sessionId) {
            rollbackInternal(sessionId, RollbackTrigger.REPLACEMENT_RUNTIME_FATAL_FAILURE)
        }
    }

    /**
     * Product Meeting end / session stop while multicast may still own audible.
     * Fence+release production track only — do not treat as fatal or require Anchor reacquire
     * (session media is already draining).
     */
    fun onSessionTeardown(sessionId: String): CutoverOutcome {
        if (activeSessionId != null && activeSessionId != sessionId) {
            return CutoverOutcome.REJECTED_INVALID_STATE
        }
        if (state == AudibleOwnershipState.ANCHOR_ACTIVE) {
            return CutoverOutcome.APPLIED
        }
        multicastPort.fenceProductionPlayout(sessionId)
        multicastPort.releaseProductionAudioTrack(sessionId)
        ReplacementCutoverObservability.logHandoff(
            sessionId,
            "SESSION_END_RELEASE_MULTICAST",
            CutoverOutcome.APPLIED,
            RollbackTrigger.SESSION_TEARDOWN.name,
        )
        state = AudibleOwnershipState.ANCHOR_ACTIVE
        activeSessionId = null
        ReplacementCutoverObservability.logState(sessionId, state, "session_teardown")
        ReplacementCutoverObservability.logRc1Field(
            "SESSION_END_OWNERSHIP_RELEASED",
            sessionId,
            RollbackTrigger.SESSION_TEARDOWN.name,
        )
        return CutoverOutcome.APPLIED
    }

    fun verifySingleOwner(sessionId: String): Boolean {
        val anchorActive = anchorPort.isAnchorAudibleActive(sessionId)
        val multicastActive = multicastPort.isProductionAudioTrackActive(sessionId)
        val pass =
            when (state) {
                AudibleOwnershipState.ANCHOR_ACTIVE -> !multicastActive
                AudibleOwnershipState.CUTOVER_ARMED -> !anchorActive && !multicastActive
                AudibleOwnershipState.MULTICAST_ACTIVE -> multicastActive && !anchorActive
            }
        ReplacementCutoverObservability.logOwnerVerify(
            sessionId,
            pass,
            anchorActive,
            multicastActive,
        )
        return pass
    }

    private fun rollbackInternal(
        sessionId: String,
        trigger: RollbackTrigger,
    ): CutoverOutcome {
        if (state == AudibleOwnershipState.ANCHOR_ACTIVE) {
            return if (trigger == RollbackTrigger.MANUAL) {
                CutoverOutcome.APPLIED
            } else {
                CutoverOutcome.REJECTED_INVALID_STATE
            }
        }

        multicastPort.fenceProductionPlayout(sessionId)
        multicastPort.releaseProductionAudioTrack(sessionId)
        ReplacementCutoverObservability.logHandoff(
            sessionId,
            "ROLLBACK_FENCE_MULTICAST",
            CutoverOutcome.APPLIED,
            trigger.name,
        )

        if (!anchorPort.acquireAnchorOwnership(sessionId)) {
            ReplacementCutoverObservability.logRollback(
                sessionId,
                trigger,
                CutoverOutcome.REJECTED_HANDOFF_FAILURE,
                "anchor_reacquire_failed",
            )
            return CutoverOutcome.REJECTED_HANDOFF_FAILURE
        }
        ReplacementCutoverObservability.logHandoff(
            sessionId,
            "ROLLBACK_RESTORE_ANCHOR",
            CutoverOutcome.APPLIED,
            trigger.name,
        )

        state = AudibleOwnershipState.ANCHOR_ACTIVE
        activeSessionId = sessionId
        val verify = verifySingleOwner(sessionId)
        if (!verify) {
            ReplacementCutoverObservability.logRollback(
                sessionId,
                trigger,
                CutoverOutcome.REJECTED_INVARIANT_VIOLATION,
            )
            return CutoverOutcome.REJECTED_INVARIANT_VIOLATION
        }

        ReplacementCutoverObservability.logState(sessionId, state, "rollback=$trigger")
        ReplacementCutoverObservability.logRollback(sessionId, trigger, CutoverOutcome.APPLIED)
        if (trigger == RollbackTrigger.MANUAL) {
            ReplacementCutoverObservability.logRc1Field("ROLLBACK_SUCCEEDED", sessionId, trigger.name)
        }
        probeOwnership(sessionId)
        return CutoverOutcome.APPLIED
    }

    private fun probeOwnership(sessionId: String) {
        val anchorActive = anchorPort.isAnchorAudibleActive(sessionId)
        val multicastActive = multicastPort.isProductionAudioTrackActive(sessionId)
        if (anchorActive && multicastActive) {
            ReplacementCutoverObservability.logOwnershipInvariantViolation(sessionId)
        }
    }
}
