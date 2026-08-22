package com.talkback.core.session

import com.talkback.core.session.failure.ConferenceFailureTerminal

/**
 * Phase 2-5 + Phase B L4 wiring seam. Coordinator collects facts; this object only projects.
 * Must not call RecoveryEdgeProvider or mutate [ConferenceTopologySnapshot].
 */
object ConferenceHealthBinder {

    fun project(
        snapshot: ConferenceTopologySnapshot?,
        localModuleId: String,
        iceStateForModule: (String) -> String?,
        recoveryFacts: EdgeRecoveryFacts,
        sessionEstablished: Boolean = false,
        failureTerminalsByModuleId: Map<String, ConferenceFailureTerminal> = emptyMap(),
        programRelayUsable: Boolean? = null,
    ): ConferenceHealth? {
        if (snapshot == null) return null
        val mediaObservations = ConferenceHealthFactsAdapter.mediaObservations(
            snapshot,
            localModuleId,
            iceStateForModule
        )
        val recovery = ConferenceHealthFactsAdapter.recoveryProgress(recoveryFacts)
        val mediaUsableResult = ConferenceL4MediaUsableContract.evaluate(
            ConferenceL4MediaUsableInput(
                snapshot = snapshot,
                localModuleId = localModuleId,
                sessionEstablished = sessionEstablished,
                mediaObservations = mediaObservations,
                failureTerminalsByModuleId = failureTerminalsByModuleId,
                programRelayUsable = programRelayUsable,
            )
        )
        val l4 = ConferenceL4Adjudicator.adjudicate(
            ConferenceL4AdjudicationInput(
                sessionEstablished = sessionEstablished,
                mediaUsableResult = mediaUsableResult,
                recovery = recovery,
            )
        )
        val projection = ConferenceHealthProjectionContract.project(
            ConferenceHealthProjectionInput(
                snapshot = snapshot,
                mediaObservations = mediaObservations,
                recovery = recovery,
            )
        )
        return ConferenceHealth.from(snapshot, projection, l4)
    }
}
