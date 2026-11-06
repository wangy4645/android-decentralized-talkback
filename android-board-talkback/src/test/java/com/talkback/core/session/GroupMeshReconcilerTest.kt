package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMeshReconcilerTest {
    private val reconciler = GroupMeshReconciler()

    @Test
    fun suppressesJoinWhileIceChecking() {
        assertFalse(reconciler.canOfferJoin("CH-01", "M02", "CHECKING"))
    }

    @Test
    fun backoffBlocksImmediateReconnect() {
        reconciler.markDisconnected("CH-01", "M02")
        assertFalse(reconciler.canReconnect("CH-01", "M02", "DISCONNECTED"))
    }

    @Test
    fun throttlesIceRestartAccept() {
        reconciler.markIceRestartAccepted("CH-01", "M02")
        assertFalse(reconciler.canAcceptIceRestart("CH-01", "M02", "DISCONNECTED"))
    }

    @Test
    fun connectedPeerSkipsReconnect() {
        reconciler.markConnected("CH-01", "M02")
        assertFalse(reconciler.canReconnect("CH-01", "M02", "CONNECTED"))
    }

    @Test
    fun recentJoinOffer_dedupsWithinWindow_thenExpires() {
        assertFalse(reconciler.hasRecentJoinOffer("CH-01", "M05"))
        reconciler.markJoinOffered("CH-01", "M05")
        val now = System.currentTimeMillis()
        assertTrue(reconciler.hasRecentJoinOffer("CH-01", "M05", now + 100L))
        assertFalse(
            reconciler.hasRecentJoinOffer("CH-01", "M05", now + GroupMeshReconciler.MIN_JOIN_OFFER_DEDUP_MS + 1L)
        )
    }

    @Test
    fun recentJoinOffer_coversReconnectAttempt_notConnectedOrIdle() {
        reconciler.markReconnectAttempt("CH-01", "M05")
        assertTrue(reconciler.hasRecentJoinOffer("CH-01", "M05"))
        reconciler.markConnected("CH-01", "M05")
        assertFalse(reconciler.hasRecentJoinOffer("CH-01", "M05"))
    }

    @Test
    fun allowsIceRestartAfterCheckingStuckWindow() {
        reconciler.markIceChecking("CH-01", "M02")
        assertFalse(reconciler.canAcceptIceRestart("CH-01", "M02", "CHECKING"))
        Thread.sleep(GroupMeshReconciler.CHECKING_STUCK_MS + 100L)
        assertTrue(reconciler.canAcceptIceRestart("CH-01", "M02", "CHECKING"))
    }

    @Test
    fun allowsReconnectOfferAfterCheckingStuckWindow() {
        reconciler.markIceChecking("CH-01", "M02")
        assertFalse(reconciler.canReconnect("CH-01", "M02", "CHECKING"))
        Thread.sleep(GroupMeshReconciler.CHECKING_STUCK_MS + 100L)
        assertTrue(reconciler.canReconnect("CH-01", "M02", "CHECKING"))
    }

    @Test
    fun reconnectSuppressReasonWhileChecking() {
        reconciler.markIceChecking("CH-01", "M02")
        assertEquals(
            "ICE_CHECKING_SUPPRESS",
            reconciler.reconnectSuppressReason("CH-01", "M02", "CHECKING")
        )
    }

    @Test
    fun reconnectSuppressReasonWhenConnected() {
        reconciler.markConnected("CH-01", "M02")
        assertEquals(
            "ICE_CONNECTED",
            reconciler.reconnectSuppressReason("CH-01", "M02", "CONNECTED")
        )
    }

    @Test
    fun checkingAgeMsTracksSettlingWindow() {
        reconciler.markIceChecking("CH-01", "M02")
        val age = reconciler.checkingAgeMs("CH-01", "M02")
        assertTrue(age in 0L..100L)
    }
}
