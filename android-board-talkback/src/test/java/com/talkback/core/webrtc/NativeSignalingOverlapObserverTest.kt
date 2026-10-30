package com.talkback.core.webrtc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Step 2b probe C: the fence must stay observable after it starts working, otherwise a field
 * PASS cannot be distinguished from the race simply not occurring in that run.
 */
class NativeSignalingOverlapObserverTest {

    private val lines = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        NativeSignalingOverlapObserver.reset()
        NativeSignalingOverlapObserver.sink = { lines.add(it) }
    }

    @After
    fun tearDown() {
        NativeSignalingOverlapObserver.reset()
        NativeSignalingOverlapObserver.sink = { }
        lines.clear()
    }

    @Test
    fun quietWhenNothingRaces() {
        val txn = PcSignalingTransaction()
        NativeSignalingOverlapObserver
            .observeRequest(SignalingOp.ADD_ICE_CANDIDATE, "edgeA", 1, 5L, txn)
            .admitted()
        assertTrue(lines.toString(), lines.isEmpty())
    }

    @Test
    fun reportsWouldHaveOverlappedWhenTheFenceAbsorbsAForeignSrd() {
        val txn = PcSignalingTransaction()
        val srdStarted = CountDownLatch(1)
        val releaseSrd = CountDownLatch(1)
        val srdThread = Thread({
            NativeSignalingOverlapObserver.beginSrd("edgeM02", 2)
            srdStarted.countDown()
            releaseSrd.await(5, TimeUnit.SECONDS)
            NativeSignalingOverlapObserver.endSrd(2)
        }, "tb-edge-m02")
        srdThread.start()
        assertTrue(srdStarted.await(3, TimeUnit.SECONDS))

        NativeSignalingOverlapObserver
            .observeRequest(SignalingOp.ADD_ICE_CANDIDATE, "edgeM04", 3, 5L, txn)
            .admitted()

        releaseSrd.countDown()
        srdThread.join(3_000)

        assertEquals(1, lines.size)
        val line = lines.first()
        assertTrue(line, line.startsWith("NATIVE_OP_DURING_SRD "))
        assertTrue(line, line.contains("op=ADD_ICE_CANDIDATE"))
        assertTrue(line, line.contains("edgeKey=edgeM04"))
        assertTrue(line, line.contains("srdInFlight=edgeM02"))
        assertTrue(line, line.contains("wouldHaveOverlappedWithoutFence=true"))
    }

    @Test
    fun ownSrdThreadIsNotReportedAsForeignOverlap() {
        NativeSignalingOverlapObserver.beginSrd("edgeSelf", 4)
        NativeSignalingOverlapObserver
            .observeRequest(SignalingOp.ADD_ICE_CANDIDATE, "edgeSelf", 4, 5L, PcSignalingTransaction())
            .admitted()
        NativeSignalingOverlapObserver.endSrd(4)
        assertTrue(lines.toString(), lines.isEmpty())
    }

    @Test
    fun getStatsDefersWhileAnySrdIsInFlight() {
        val srdStarted = CountDownLatch(1)
        val releaseSrd = CountDownLatch(1)
        val srdThread = Thread({
            NativeSignalingOverlapObserver.beginSrd("edgeM02", 2)
            srdStarted.countDown()
            releaseSrd.await(5, TimeUnit.SECONDS)
            NativeSignalingOverlapObserver.endSrd(2)
        }, "tb-edge-m02")
        srdThread.start()
        assertTrue(srdStarted.await(3, TimeUnit.SECONDS))
        assertTrue(NativeSignalingOverlapObserver.shouldDeferGetStats())

        NativeSignalingOverlapObserver.logGetStatsDeferred("edgeM04", 3, 5L)

        releaseSrd.countDown()
        srdThread.join(3_000)

        assertEquals(1, lines.size)
        val line = lines.first()
        assertTrue(line, line.startsWith("GET_STATS_DEFERRED_DURING_SRD "))
        assertTrue(line, line.contains("srdInFlight=edgeM02"))
        assertTrue(line, line.contains("wouldHaveOverlappedWithoutFence=true"))
    }

    @Test
    fun getStatsNotDeferredWhenNoSrdInFlight() {
        assertFalse(NativeSignalingOverlapObserver.shouldDeferGetStats())
        NativeSignalingOverlapObserver.logGetStatsDeferred("edgeM04", 3, 5L)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun rejectedWorkIsReportedWithItsAdmissionVerdict() {
        val txn = PcSignalingTransaction()
        txn.close { }
        val observation = NativeSignalingOverlapObserver
            .observeRequest(SignalingOp.ADD_ICE_CANDIDATE, "edgeA", 1, 5L, txn)
        observation.rejected(PcSignalingTransaction.Admission.REJECTED_CLOSED)
        assertEquals(1, lines.size)
        assertTrue(lines.first(), lines.first().contains("admission=REJECTED_CLOSED"))
    }
}
