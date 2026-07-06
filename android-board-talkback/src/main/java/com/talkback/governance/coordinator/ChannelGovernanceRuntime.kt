package com.talkback.governance.coordinator

import com.talkback.governance.GovernanceObservabilityLog
import com.talkback.governance.capability.capabilitySnapshot
import com.talkback.governance.gate.GateDecision
import com.talkback.governance.gate.Operation
import com.talkback.governance.gate.OperationGate
import com.talkback.governance.transition.BeginTransitionResult
import com.talkback.governance.transition.TransitionCoordinator
import com.talkback.governance.transition.TransitionRecord
import com.talkback.governance.transition.TransitionPredicateEval
import com.talkback.governance.transition.TransitionTrigger

class ChannelGovernanceRuntime(host: ChannelGovernanceHost) {
    private val channelProbes = CoordinatorCapabilityProbes(host)
    val transitionCoordinator = TransitionCoordinator(channelProbes.channelProbes)
    private val operationGate = OperationGate()
    private val unicastSnapshot = capabilitySnapshot(UNICAST_CHANNEL_ID, channelProbes.unicastProbes)

    fun canStart(operation: Operation, channelId: String): GateDecision {
        transitionCoordinator.expireTimeouts(channelId).forEach(GovernanceObservabilityLog::transitionTerminal)
        val snapshot = if (operation == Operation.SINGLE_CALL) {
            unicastSnapshot
        } else {
            transitionCoordinator.capabilitySnapshot(channelId)
        }
        val active = transitionCoordinator.activeTransition(channelId)
        val decision = operationGate.canStart(operation, channelId, snapshot, active)
        GovernanceObservabilityLog.gateDecision(operation, channelId, decision)
        return decision
    }

    fun beginTransition(trigger: TransitionTrigger, channelId: String): BeginTransitionResult {
        val result = transitionCoordinator.beginTransition(trigger, channelId)
        GovernanceObservabilityLog.transitionBegin(result)
        if (result is BeginTransitionResult.Started) {
            transitionCoordinator.markReconciling(channelId)
        }
        return result
    }

    fun maybeCompleteRecovery(channelId: String, recoveryReady: Boolean) {
        maybeCompleteTransition(channelId, TransitionTrigger.MEETING_END) {
            if (recoveryReady) {
                TransitionPredicateEval.satisfied("group_operational")
            } else {
                TransitionPredicateEval.unsatisfied("group_not_operational")
            }
        }
    }

    fun maybeCompleteMeetingStart(channelId: String, eval: TransitionPredicateEval) {
        maybeCompleteTransition(channelId, TransitionTrigger.MEETING_START) { eval }
    }

    fun failMeetingStart(channelId: String, reason: String) {
        val active = transitionCoordinator.activeTransition(channelId) ?: return
        if (!active.isActive || active.trigger != TransitionTrigger.MEETING_START) return
        val failed = transitionCoordinator.failTransition(channelId, reason)
        if (failed != null) {
            GovernanceObservabilityLog.transitionTerminal(failed)
        }
    }

    private fun maybeCompleteTransition(
        channelId: String,
        trigger: TransitionTrigger,
        evalProvider: () -> TransitionPredicateEval
    ) {
        val active = transitionCoordinator.activeTransition(channelId) ?: return
        if (!active.isActive || active.trigger != trigger) return
        val eval = evalProvider()
        GovernanceObservabilityLog.transitionPredicateEval(channelId, trigger, eval)
        if (!eval.satisfied) return
        val completed = transitionCoordinator.completeTransition(channelId)
        if (completed != null) {
            GovernanceObservabilityLog.transitionTerminal(completed)
        }
    }

    fun activeTransition(channelId: String): TransitionRecord? =
        transitionCoordinator.activeTransition(channelId)

    companion object {
        const val UNICAST_CHANNEL_ID: String = "__unicast__"
    }
}
