package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInviteExecutionSupportTest {

    @Test
    fun evaluateProvisionAttempt_proceedsWhenNoInflight() {
        assertEquals(
            GroupInviteExecutionSupport.ProvisionGate.Proceed,
            GroupInviteExecutionSupport.evaluateProvisionAttempt(
                wireInFlight = false,
                engineProvisionInFlight = false,
            ),
        )
    }

    @Test
    fun evaluateProvisionAttempt_skipsWireInFlightFirst() {
        assertEquals(
            GroupInviteExecutionSupport.ProvisionGate.SkipWireInFlight,
            GroupInviteExecutionSupport.evaluateProvisionAttempt(
                wireInFlight = true,
                engineProvisionInFlight = false,
            ),
        )
    }

    @Test
    fun evaluateProvisionAttempt_skipsEngineProvisionInFlight() {
        assertEquals(
            GroupInviteExecutionSupport.ProvisionGate.SkipEngineProvisionInFlight,
            GroupInviteExecutionSupport.evaluateProvisionAttempt(
                wireInFlight = false,
                engineProvisionInFlight = true,
            ),
        )
    }

    @Test
    fun countsAsDispatched_includesEngineDeferred() {
        assertTrue(
            GroupInviteExecutionSupport.countsAsDispatched(
                GroupInviteExecutionSupport.DispatchOutcome.EngineDeferred,
            )
        )
        assertTrue(
            GroupInviteExecutionSupport.countsAsDispatched(
                GroupInviteExecutionSupport.DispatchOutcome.WireSent,
            )
        )
        assertFalse(
            GroupInviteExecutionSupport.countsAsDispatched(
                GroupInviteExecutionSupport.DispatchOutcome.SkippedDuplicate,
            )
        )
    }

    @Test
    fun provisionKey_storageKeyIsStable() {
        val key = GroupInviteExecutionSupport.provisionKey("grp:CH-01", "M02")
        assertEquals("grp:CH-01|M02", key.storageKey())
    }
}
