package com.talkback.governance.transition

data class TransitionPredicateEval(
    val satisfied: Boolean,
    val reason: String
) {
    companion object {
        fun satisfied(reason: String) = TransitionPredicateEval(satisfied = true, reason = reason)
        fun unsatisfied(reason: String) = TransitionPredicateEval(satisfied = false, reason = reason)
    }
}
