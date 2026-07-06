package com.talkback.governance.gate

import com.talkback.governance.capability.Capability
import com.talkback.governance.capability.CapabilityReadiness
import com.talkback.governance.capability.CapabilitySnapshot
import com.talkback.governance.transition.TransitionId
import com.talkback.governance.transition.TransitionPhase
import com.talkback.governance.transition.TransitionRecord
import com.talkback.governance.transition.TransitionTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatePriorityResolverTest {
    @Test
    fun readiness_ranks_above_policy() {
        val resolved = GatePriorityResolver.resolve(
            reasons = listOf(
                PolicyReason.CooldownActive,
                ReadinessReason.RoutingNotReady
            ),
            blockingCapabilities = mapOf(ReadinessReason.RoutingNotReady to Capability.Routing)
        )

        assertEquals(ReadinessReason.RoutingNotReady, resolved.primary)
        assertEquals(listOf(PolicyReason.CooldownActive), resolved.additional)
        assertEquals(Capability.Routing, resolved.blockingCapability)
    }

    @Test
    fun routing_ranks_above_authority_within_readiness() {
        val resolved = GatePriorityResolver.resolve(
            reasons = listOf(
                ReadinessReason.AuthorityNotReady,
                ReadinessReason.RoutingNotReady
            ),
            blockingCapabilities = mapOf(
                ReadinessReason.AuthorityNotReady to Capability.Authority,
                ReadinessReason.RoutingNotReady to Capability.Routing
            )
        )

        assertEquals(ReadinessReason.RoutingNotReady, resolved.primary)
        assertEquals(ReadinessReason.AuthorityNotReady, resolved.additional.single())
    }
}

class OperationGateTest {
    private val gate = OperationGate()
    private val channelId = "CH-01"

    @Test
    fun allow_when_required_capabilities_ready() {
        val decision = gate.canStart(
            operation = Operation.PTT,
            channelId = channelId,
            snapshot = readyPttSnapshot(),
            activeTransition = null
        )

        assertTrue(decision is GateDecision.Allow)
    }

    @Test
    fun block_ptt_when_routing_not_ready() {
        val decision = gate.canStart(
            operation = Operation.PTT,
            channelId = channelId,
            snapshot = CapabilitySnapshot(
                channelId,
                mapOf(
                    Capability.Membership to CapabilityReadiness.READY,
                    Capability.Authority to CapabilityReadiness.READY,
                    Capability.Routing to CapabilityReadiness.NOT_READY
                )
            ),
            activeTransition = null
        ) as GateDecision.Blocked

        assertEquals(ReadinessReason.RoutingNotReady, decision.primaryReason)
        assertEquals(Capability.Routing, decision.blockingCapability)
    }

    @Test
    fun block_ptt_when_routing_reconciling_during_active_transition() {
        val decision = gate.canStart(
            operation = Operation.PTT,
            channelId = channelId,
            snapshot = CapabilitySnapshot(
                channelId,
                mapOf(
                    Capability.Membership to CapabilityReadiness.READY,
                    Capability.Authority to CapabilityReadiness.READY,
                    Capability.Routing to CapabilityReadiness.RECONCILING
                )
            ),
            activeTransition = activeTransition(TransitionId(7L))
        ) as GateDecision.Blocked

        assertEquals(PolicyReason.TransitionInProgress, decision.primaryReason)
    }

    @Test
    fun block_with_transition_in_progress_as_policy_when_readiness_also_fails() {
        val decision = gate.canStart(
            operation = Operation.PTT,
            channelId = channelId,
            snapshot = CapabilitySnapshot(
                channelId,
                mapOf(
                    Capability.Membership to CapabilityReadiness.READY,
                    Capability.Authority to CapabilityReadiness.NOT_READY,
                    Capability.Routing to CapabilityReadiness.NOT_READY
                )
            ),
            activeTransition = activeTransition(TransitionId(42L))
        ) as GateDecision.Blocked

        assertEquals(ReadinessReason.RoutingNotReady, decision.primaryReason)
        assertTrue(decision.additionalReasons.contains(PolicyReason.TransitionInProgress))
        assertEquals(TransitionId(42L), decision.transitionId)
    }

    private fun readyPttSnapshot(): CapabilitySnapshot = CapabilitySnapshot(
        channelId,
        mapOf(
            Capability.Membership to CapabilityReadiness.READY,
            Capability.Authority to CapabilityReadiness.READY,
            Capability.Routing to CapabilityReadiness.READY
        )
    )

    @Test
    fun allow_meetingInvite_duringMeetingStartTransition() {
        val decision = gate.canStart(
            operation = Operation.MEETING_INVITE,
            channelId = channelId,
            snapshot = CapabilitySnapshot(
                channelId,
                mapOf(Capability.Conference to CapabilityReadiness.READY)
            ),
            activeTransition = activeTransition(TransitionId(9L), TransitionTrigger.MEETING_START)
        )

        assertTrue(decision is GateDecision.Allow)
    }

    private fun activeTransition(
        id: TransitionId,
        trigger: TransitionTrigger = TransitionTrigger.MEETING_END
    ): TransitionRecord = TransitionRecord(
        id = id,
        channelId = channelId,
        trigger = trigger,
        phase = TransitionPhase.RECONCILING,
        startedAtMs = 1_000L,
        deadlineMs = 13_000L
    )
}
