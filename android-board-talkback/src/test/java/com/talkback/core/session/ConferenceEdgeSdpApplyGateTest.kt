package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ConferenceEdgeSdpApplyGateTest {

    @Test
    fun timeoutWins_lateSettleIgnored() {
        val gate = ConferenceEdgeSdpApplyGate()
        gate.begin(EDGE)
        assertTrue(gate.isApplying(EDGE))
        assertTrue(gate.tryTimeout(EDGE))
        assertFalse(gate.isApplying(EDGE))
        assertFalse("late JNI return must not settle after SRD_TIMEOUT", gate.trySettle(EDGE))
    }

    @Test
    fun settleWins_timeoutIgnored() {
        val gate = ConferenceEdgeSdpApplyGate()
        gate.begin(EDGE)
        assertTrue(gate.trySettle(EDGE))
        assertFalse(gate.tryTimeout(EDGE))
        assertFalse(gate.isApplying(EDGE))
    }

    @Test
    fun blockedApply_timeoutMarksFailedWithoutWaitingJni() {
        val gate = ConferenceEdgeSdpApplyGate()
        val hold = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        val media = PeerMediaExecutors(threadNamePrefix = "test-edge")
        val coordinator = Executors.newSingleThreadExecutor { r ->
            Thread(r, "talkback-coordinator")
        }
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        try {
            gate.begin(EDGE)
            ConferenceMediaJniAffinity.dispatch(media, "conf-1", "M03") {
                hold.await(10, TimeUnit.SECONDS)
                gate.trySettle(EDGE)
            }
            val timedOut = CountDownLatch(1)
            watchdog.schedule({
                coordinator.execute {
                    if (gate.tryTimeout(EDGE)) {
                        failed.set(true)
                        timedOut.countDown()
                    }
                }
            }, 50L, TimeUnit.MILLISECONDS)
            assertTrue("EDGE_FAILED must not wait on hung SRD", timedOut.await(1, TimeUnit.SECONDS))
            assertTrue(failed.get())
            assertFalse(gate.trySettle(EDGE))
        } finally {
            hold.countDown()
            watchdog.shutdownNow()
            media.shutdownAll()
            coordinator.shutdownNow()
        }
    }

    companion object {
        private const val EDGE = "conf-1|M03"
    }
}
