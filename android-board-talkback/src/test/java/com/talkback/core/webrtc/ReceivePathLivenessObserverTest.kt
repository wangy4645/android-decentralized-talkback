package com.talkback.core.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivePathLivenessObserverTest {

    @Test
    fun receivePathLive_falseUntilDebounceElapsed() {
        var now = 0L
        val observer = ReceivePathLivenessObserver(
            debounceMs = 500L,
            clock = { now }
        )

        observer.onInboundPcm("sess-1", "M01")
        now = 100L
        observer.onInboundPcm("sess-1", "M01")
        assertFalse(observer.receivePathLive("sess-1", "M01"))

        now = 500L
        observer.onInboundPcm("sess-1", "M01")
        assertTrue(observer.receivePathLive("sess-1", "M01"))
    }

    @Test
    fun receivePathLive_goesFalseAfterPcmGap() {
        var now = 0L
        val observer = ReceivePathLivenessObserver(
            debounceMs = 500L,
            clock = { now }
        )

        repeat(6) { step ->
            now = step * 100L
            observer.onInboundPcm("sess-1", "M01")
        }
        assertTrue(observer.receivePathLive("sess-1", "M01"))

        now = 1_200L
        assertFalse(observer.receivePathLive("sess-1", "M01"))
    }

    @Test
    fun receivePathLive_gapResetsAccumulation() {
        var now = 0L
        val observer = ReceivePathLivenessObserver(
            debounceMs = 500L,
            clock = { now }
        )

        observer.onInboundPcm("sess-1", "M01")
        now = 700L
        observer.onInboundPcm("sess-1", "M01")
        assertFalse(observer.receivePathLive("sess-1", "M01"))

        now = 1_000L
        observer.onInboundPcm("sess-1", "M01")
        assertFalse(observer.receivePathLive("sess-1", "M01"))

        now = 1_500L
        observer.onInboundPcm("sess-1", "M01")
        assertTrue(observer.receivePathLive("sess-1", "M01"))
    }

    @Test
    fun clearSession_resetsPeerState() {
        var now = 0L
        val observer = ReceivePathLivenessObserver(
            debounceMs = 500L,
            clock = { now }
        )

        repeat(6) {
            now = it * 100L
            observer.onInboundPcm("sess-1", "M01")
        }
        assertTrue(observer.receivePathLive("sess-1", "M01"))

        observer.clearSession("sess-1")
        assertFalse(observer.receivePathLive("sess-1", "M01"))
    }
}
