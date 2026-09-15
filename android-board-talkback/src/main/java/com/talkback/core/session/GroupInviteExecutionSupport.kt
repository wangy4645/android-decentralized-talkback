package com.talkback.core.session

/**
 * ADR-0057 GIE — invite intent vs engine provision vs wire-send separation.
 */
object GroupInviteExecutionSupport {

    data class ProvisionAttemptKey(
        val sessionId: String,
        val remoteModuleId: String,
    ) {
        fun storageKey(): String = "$sessionId|$remoteModuleId"
    }

    fun provisionKey(sessionId: String, remoteModuleId: String): ProvisionAttemptKey =
        ProvisionAttemptKey(sessionId, remoteModuleId)

    enum class ProvisionGate {
        /** May queue engine provision and eventually emit wire INVITE. */
        Proceed,

        /** Active outbound GROUP_INVITE attempt — do not start another wire send. */
        SkipWireInFlight,

        /** Engine provision already queued for this peer — retain intent, no duplicate queue. */
        SkipEngineProvisionInFlight,
    }

    sealed interface DispatchOutcome {
        /** Valid GROUP_INVITE handed off on wire (sync or async completion). */
        data object WireSent : DispatchOutcome

        /** Engine provision deferred; intent retained for lifecycle completion. */
        data object EngineDeferred : DispatchOutcome

        /** Duplicate reconcile suppressed (wire or provision in flight). */
        data object SkippedDuplicate : DispatchOutcome

        /** Preconditions missing (peer not discovered, session gone, etc.). */
        data object SkippedNotReady : DispatchOutcome
    }

    fun evaluateProvisionAttempt(
        wireInFlight: Boolean,
        engineProvisionInFlight: Boolean,
    ): ProvisionGate =
        when {
            wireInFlight -> ProvisionGate.SkipWireInFlight
            engineProvisionInFlight -> ProvisionGate.SkipEngineProvisionInFlight
            else -> ProvisionGate.Proceed
        }

    fun countsAsDispatched(outcome: DispatchOutcome): Boolean =
        outcome == DispatchOutcome.WireSent || outcome == DispatchOutcome.EngineDeferred
}
