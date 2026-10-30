package com.talkback.core.conference.session.profile01

/**
 * P0.2 host-side gate: Profile01 rejoin catch-up is not tied to GROUP_ACCEPT receipt.
 * Dispatch only after accept lineage/handoff succeeded (or equivalent rejoin-ready paths).
 */
object Profile01RejoinCatchUpHostDispatchGate {
    fun mayDispatchOnGroupAcceptIngress(): Boolean = false

    fun mayDispatchAfterAcceptFailClosed(): Boolean = false

    fun mayDispatchAfterAdmissionMediaSettled(applyError: Throwable?): Boolean = applyError == null

    fun mayDispatchAfterMembershipOnlyAccept(): Boolean = true

    fun mayDispatchAfterMeshAlreadyConnectedAccept(): Boolean = true
}
