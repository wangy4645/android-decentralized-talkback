package com.talkback.core.session.gbc

/**
 * C-R2: legacy symbols that MUST NOT be read as generation authority evidence.
 * Convergence Module and harness assert against this denylist.
 */
object GbcLegacyAuthorityProxies {
    const val BOOTSTRAP_ATTEMPT_COUNT = "bootstrapAttemptCount"
    const val WAITING_FOR_PRIMARY = "waitingForPrimary"
    const val OBSERVE_BOOTSTRAP_ATTEMPT = "observeGroupTransitionBootstrapAttempt"
    const val MESH_CALL_OR_INVITE = "meshCallInternal_or_GROUP_INVITE"
    const val HELLO_OBSERVATION = "HELLO_observation"

    val ALL: Set<String> =
        setOf(
            BOOTSTRAP_ATTEMPT_COUNT,
            WAITING_FOR_PRIMARY,
            OBSERVE_BOOTSTRAP_ATTEMPT,
            MESH_CALL_OR_INVITE,
            HELLO_OBSERVATION,
        )

    /**
     * Documentation seam for Coordinator: these remain valid for scheduler/diagnostics
     * but are not inputs to [GroupBootstrapConvergence] classification/obligation.
     */
    fun isForbiddenAuthorityInput(name: String): Boolean = name in ALL
}
