package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeReachabilitySnapshotTest {

    @Test
    fun gate_s13bSoak_routeNotConverged_blocksDispatch() {
        val snap = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            routeConverged = false,
            authorityReachable = true
        )
        assertFalse(snap.canDispatchRecoverySignal())
        assertEquals(RecoveryWaitingReason.WAITING_FOR_ROUTE, snap.dispatchWaitingReason())
    }

    @Test
    fun gate_allFactsReady_allowsDispatch() {
        val snap = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            routeConverged = true,
            authorityReachable = false
        )
        assertTrue(snap.canDispatchRecoverySignal())
        assertNull(snap.dispatchWaitingReason())
        assertFalse(snap.canCompleteRecovery())
    }

    @Test
    fun gate_completionRequiresAuthority() {
        val snap = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            routeConverged = true,
            authorityReachable = true
        )
        assertTrue(snap.canCompleteRecovery())
    }
}
