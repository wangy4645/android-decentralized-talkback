package com.talkback.core.util

import com.talkback.core.session.GroupInviteDeliveryRecoverySupport

object GroupInviteDeliveryRecoveryLog {

    fun reconcileEvaluated(
        channelId: String,
        peerModuleId: String,
        sessionId: String,
        phase: GroupInviteDeliveryRecoverySupport.DeliveryPhase,
        gate: GroupInviteDeliveryRecoverySupport.ReconciliationGate,
    ): String =
        "GIDR_RECONCILE_EVALUATED ch=$channelId peer=$peerModuleId session=$sessionId " +
            "phase=$phase gate=$gate"

    fun executionOpportunity(
        channelId: String,
        peerModuleId: String,
        sessionId: String,
        origin: String,
    ): String =
        "GIDR_EXECUTION_OPPORTUNITY ch=$channelId peer=$peerModuleId session=$sessionId origin=$origin"

    fun deliverySatisfied(
        channelId: String,
        peerModuleId: String,
        sessionId: String,
        reason: String,
    ): String =
        "GIDR_DELIVERY_SATISFIED ch=$channelId peer=$peerModuleId session=$sessionId reason=$reason"
}
