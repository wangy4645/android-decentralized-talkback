package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInviteInboundExecutionSupportTest {

    @Test
    fun evaluateAcceptAttempt_proceedWhenOpen() {
        assertEquals(
            GroupInviteInboundExecutionSupport.AcceptGate.Proceed,
            GroupInviteInboundExecutionSupport.evaluateAcceptAttempt(
                acceptComplete = false,
                engineProvisionInFlight = false,
            ),
        )
    }

    @Test
    fun evaluateAcceptAttempt_skipWhenAcceptComplete() {
        assertEquals(
            GroupInviteInboundExecutionSupport.AcceptGate.SkipAcceptComplete,
            GroupInviteInboundExecutionSupport.evaluateAcceptAttempt(
                acceptComplete = true,
                engineProvisionInFlight = false,
            ),
        )
    }

    @Test
    fun evaluateAcceptAttempt_skipWhenProvisionInFlight() {
        assertEquals(
            GroupInviteInboundExecutionSupport.AcceptGate.SkipEngineProvisionInFlight,
            GroupInviteInboundExecutionSupport.evaluateAcceptAttempt(
                acceptComplete = false,
                engineProvisionInFlight = true,
            ),
        )
    }

    @Test
    fun evaluateAcceptAttempt_reconnectWithIceNotConnected_proceedsDespiteAcceptComplete() {
        // 11-06 M02↔M03 deadlock: answerer must apply a new-lineage offer when ICE never came up.
        assertEquals(
            GroupInviteInboundExecutionSupport.AcceptGate.Proceed,
            GroupInviteInboundExecutionSupport.evaluateAcceptAttempt(
                acceptComplete = true,
                engineProvisionInFlight = false,
                acceptPath = GroupInviteInboundExecutionSupport.AcceptPath.Reconnect,
                iceConnected = false,
            ),
        )
    }

    @Test
    fun evaluateAcceptAttempt_reconnectWithIceConnected_stillSkips() {
        assertEquals(
            GroupInviteInboundExecutionSupport.AcceptGate.SkipAcceptComplete,
            GroupInviteInboundExecutionSupport.evaluateAcceptAttempt(
                acceptComplete = true,
                engineProvisionInFlight = false,
                acceptPath = GroupInviteInboundExecutionSupport.AcceptPath.Reconnect,
                iceConnected = true,
            ),
        )
    }

    @Test
    fun evaluateAcceptAttempt_freshPathAcceptComplete_skipsEvenWhenIceDown() {
        assertEquals(
            GroupInviteInboundExecutionSupport.AcceptGate.SkipAcceptComplete,
            GroupInviteInboundExecutionSupport.evaluateAcceptAttempt(
                acceptComplete = true,
                engineProvisionInFlight = false,
                acceptPath = GroupInviteInboundExecutionSupport.AcceptPath.Fresh,
                iceConnected = false,
            ),
        )
    }

    @Test
    fun evaluateAcceptAttempt_reconnectProvisionInFlight_stillDefersToInFlight() {
        assertEquals(
            GroupInviteInboundExecutionSupport.AcceptGate.SkipEngineProvisionInFlight,
            GroupInviteInboundExecutionSupport.evaluateAcceptAttempt(
                acceptComplete = true,
                engineProvisionInFlight = true,
                acceptPath = GroupInviteInboundExecutionSupport.AcceptPath.Reconnect,
                iceConnected = false,
            ),
        )
    }

    @Test
    fun countsAsDispatched_acceptsDeferred() {
        assertTrue(
            GroupInviteInboundExecutionSupport.countsAsDispatched(
                GroupInviteInboundExecutionSupport.DispatchOutcome.EngineDeferred,
            ),
        )
        assertFalse(
            GroupInviteInboundExecutionSupport.countsAsDispatched(
                GroupInviteInboundExecutionSupport.DispatchOutcome.SkippedDuplicate,
            ),
        )
    }
}
