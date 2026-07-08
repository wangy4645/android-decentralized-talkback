package com.talkback.governance.transition

data class TransitionPolicyRule(
    val timeoutMs: Long,
    val blocksOperationsDuringTransition: Boolean = true,
    val inviteDispatch: InviteDispatchPolicy? = null
)

object TransitionPolicy {
    fun buildRules(): Map<TransitionTrigger, TransitionPolicyRule> = mapOf(
        TransitionTrigger.MEETING_END to TransitionPolicyRule(timeoutMs = 12_000L),
        TransitionTrigger.MEETING_START to TransitionPolicyRule(
            timeoutMs = 12_000L,
            inviteDispatch = InviteDispatchPolicy.MEETING_START_DEFAULT
        ),
        TransitionTrigger.GROUP_BOOTSTRAP to TransitionPolicyRule(timeoutMs = 10_000L),
        TransitionTrigger.IDENTITY_REBOUND to TransitionPolicyRule(timeoutMs = 15_000L),
        TransitionTrigger.HOST_FAILOVER to TransitionPolicyRule(timeoutMs = 12_000L),
        TransitionTrigger.REJOIN to TransitionPolicyRule(timeoutMs = 10_000L),
        TransitionTrigger.NETWORK_CHANGE to TransitionPolicyRule(timeoutMs = 10_000L),
        TransitionTrigger.UNICAST_SUSPEND_GROUP to TransitionPolicyRule(timeoutMs = 5_000L),
        TransitionTrigger.UNICAST_RESUME_GROUP to TransitionPolicyRule(timeoutMs = 10_000L)
    )

    fun rule(trigger: TransitionTrigger): TransitionPolicyRule = PolicyRegistry.rule(trigger)

    fun timeoutMs(trigger: TransitionTrigger): Long = rule(trigger).timeoutMs

    fun meetingStartInviteDispatch(): InviteDispatchPolicy =
        rule(TransitionTrigger.MEETING_START).inviteDispatch
            ?: throw PolicyConfigurationException(TransitionTrigger.MEETING_START)
}
