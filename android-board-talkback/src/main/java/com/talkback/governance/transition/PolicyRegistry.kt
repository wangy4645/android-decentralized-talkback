package com.talkback.governance.transition

class PolicyStartupException(errors: List<String>) :
    IllegalStateException("TransitionPolicy validation failed: ${errors.joinToString("; ")}")

class PolicyConfigurationException(trigger: TransitionTrigger) :
    IllegalStateException("No TransitionPolicy for trigger=$trigger")

data class PolicyValidationResult(
    val errors: List<String>
) {
    val isValid: Boolean get() = errors.isEmpty()
}

object PolicyRegistry {
    @Volatile
    private var validatedSnapshot: Map<TransitionTrigger, TransitionPolicyRule>? = null

    fun validate(
        rules: Map<TransitionTrigger, TransitionPolicyRule> = TransitionPolicy.buildRules()
    ): PolicyValidationResult {
        val errors = mutableListOf<String>()
        TransitionTrigger.entries.forEach { trigger ->
            val rule = rules[trigger]
            if (rule == null) {
                errors += "Missing policy for trigger=$trigger"
                return@forEach
            }
            if (rule.timeoutMs <= 0L) {
                errors += "trigger=$trigger timeoutMs must be > 0"
            }
            if (trigger == TransitionTrigger.MEETING_START) {
                val dispatch = rule.inviteDispatch
                if (dispatch == null) {
                    errors += "MEETING_START requires inviteDispatch policy"
                } else {
                    errors += validateInviteDispatch(dispatch)
                }
            }
        }
        return PolicyValidationResult(errors)
    }

    fun ensureValidated(
        rulesProvider: () -> Map<TransitionTrigger, TransitionPolicyRule> = TransitionPolicy::buildRules
    ) {
        if (validatedSnapshot != null) return
        synchronized(this) {
            if (validatedSnapshot != null) return
            val rules = rulesProvider()
            val result = validate(rules)
            if (!result.isValid) {
                throw PolicyStartupException(result.errors)
            }
            validatedSnapshot = rules
        }
    }

    fun rule(trigger: TransitionTrigger): TransitionPolicyRule {
        ensureValidated()
        return validatedSnapshot!![trigger] ?: throw PolicyConfigurationException(trigger)
    }

    internal fun resetForTests() {
        synchronized(this) {
            validatedSnapshot = null
        }
    }

    private fun validateInviteDispatch(policy: InviteDispatchPolicy): List<String> {
        val errors = mutableListOf<String>()
        val overlap = policy.retryableErrors.intersect(policy.nonRetryableErrors)
        if (overlap.isNotEmpty()) {
            errors += "inviteDispatch retryable/nonRetryable overlap: $overlap"
        }
        if (policy.maxRetry < 0) {
            errors += "inviteDispatch maxRetry must be >= 0"
        }
        if (policy.deadlineMs <= 0L) {
            errors += "inviteDispatch deadlineMs must be > 0"
        }
        if (policy.initialDelayMs < 0L) {
            errors += "inviteDispatch initialDelayMs must be >= 0"
        }
        return errors
    }
}
