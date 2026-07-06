package com.talkback.governance

import com.talkback.core.util.TalkbackLog
import com.talkback.governance.gate.GateDecision
import com.talkback.governance.gate.Operation
import com.talkback.governance.transition.BeginTransitionResult
import com.talkback.governance.transition.TransitionPredicateEval
import com.talkback.governance.transition.TransitionRecord
import com.talkback.governance.transition.TransitionTrigger

object GovernanceObservabilityLog {
    fun gateDecision(operation: Operation, channelId: String, decision: GateDecision) {
        when (decision) {
            is GateDecision.Allow -> TalkbackLog.i(
                "GATE_DECISION op=$operation ch=$channelId result=ALLOW transition=${decision.transitionId}"
            )
            is GateDecision.Blocked -> TalkbackLog.i(
                buildString {
                    append("GATE_DECISION op=").append(operation)
                    append(" ch=").append(channelId)
                    append(" result=BLOCK")
                    append(" category=").append(decision.category)
                    append(" primary=").append(decision.primaryReason.code)
                    if (decision.additionalReasons.isNotEmpty()) {
                        append(" additional=").append(
                            decision.additionalReasons.joinToString(",") { it.code }
                        )
                    }
                    append(" transition=").append(decision.transitionId)
                    decision.blockingCapability?.let { append(" capability=").append(it) }
                    decision.retryAfterMs?.let { append(" retryAfterMs=").append(it) }
                }
            )
        }
    }

    fun transitionBegin(result: BeginTransitionResult) {
        when (result) {
            is BeginTransitionResult.Started -> TalkbackLog.i(
                "TRANSITION_BEGIN id=${result.record.id} ch=${result.record.channelId} " +
                    "trigger=${result.record.trigger} deadlineMs=${result.record.deadlineMs}"
            )
            is BeginTransitionResult.Rejected -> TalkbackLog.i(
                "TRANSITION_BEGIN_REJECTED ch=${result.active.channelId} " +
                    "active=${result.active.id} trigger=${result.active.trigger}"
            )
        }
    }

    fun transitionTerminal(record: TransitionRecord) {
        TalkbackLog.i(
            "TRANSITION_TERMINAL id=${record.id} ch=${record.channelId} " +
                "trigger=${record.trigger} terminal=${record.terminal} " +
                "durationMs=${record.terminalAtMs?.minus(record.startedAtMs)}"
        )
    }

    fun transitionPredicateEval(
        channelId: String,
        trigger: TransitionTrigger,
        eval: TransitionPredicateEval
    ) {
        TalkbackLog.i(
            "TRANSITION_PREDICATE_EVAL ch=$channelId trigger=$trigger " +
                "satisfied=${eval.satisfied} reason=${eval.reason}"
        )
    }
}
