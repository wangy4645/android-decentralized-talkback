package com.talkback.core.session

/**
 * OPS-07: host-side GROUP_ACCEPT (offerer) action — correlate before engine lookup;
 * never fail-open create on correlated accept.
 */
object ConferenceHostGroupAcceptSupport {

    enum class Action {
        APPLY_TO_EXISTING_ENGINE,
        FAIL_CLOSED_RECOVERY,
    }

    fun resolveAction(
        correlation: ConferenceRealizationLineage.Correlation,
        enginePresent: Boolean,
    ): Action = when {
        correlation == ConferenceRealizationLineage.Correlation.MATCH && enginePresent ->
            Action.APPLY_TO_EXISTING_ENGINE
        correlation == ConferenceRealizationLineage.Correlation.MATCH && !enginePresent ->
            Action.FAIL_CLOSED_RECOVERY
        else -> Action.FAIL_CLOSED_RECOVERY
    }
}
