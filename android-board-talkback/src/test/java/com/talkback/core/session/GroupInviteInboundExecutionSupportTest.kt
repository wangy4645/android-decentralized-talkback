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
