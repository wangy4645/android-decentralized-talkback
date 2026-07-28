package com.talkback.core.signaling.link

import com.talkback.core.signaling.SignalingTransportManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class LinkQualificationTrackerTest {

    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        LinkQualificationTrace.resetForTest { lines.add(it) }
    }

    @After
    fun tearDown() {
        LinkQualificationTrace.resetForTest(null)
    }

    private fun trackerWithoutScheduler(): LinkQualificationTracker =
        LinkQualificationTracker(scheduler = null)

    private fun stateChanges(): List<String> =
        lines.filter { it.startsWith("LINK_QUALIFICATION_STATE_CHANGED") }

    @Test
    fun ut1_successSequence_reachesBidirectionalReady_withThreeStateChanges() {
        val tracker = trackerWithoutScheduler()
        val socketId = 3L
        val generation = 2L
        val networkId = "104"

        tracker.onSocketBound(socketId, generation, networkId)
        tracker.onReceiveLoopStarted(socketId, generation)
        tracker.onFirstOutboundAfterRebind(socketId, generation)
        tracker.onFirstInboundAfterRebind(socketId, generation)

        val snapshot = tracker.snapshot()
        assertEquals(LinkQualificationState.BIDIRECTIONAL_READY, snapshot.linkQualification)
        assertEquals(3, stateChanges().size)
        assertTrue(stateChanges().last().contains("newState=BIDIRECTIONAL_READY"))
        assertTrue(stateChanges().last().contains("reason=FIRST_INBOUND_AFTER_REBIND"))
        assertTrue(lines.any { it.contains("LINK_FACT_RECEIVED") && it.contains("fact=FIRST_INBOUND_AFTER_REBIND") })
    }

    @Test
    fun ut2_outboundOnlyThenTimeout_becomesUnqualified_withRetryRequested() {
        val tracker = trackerWithoutScheduler()
        val socketId = 4L
        val generation = 4L
        val networkId = "104"

        tracker.onSocketBound(socketId, generation, networkId)
        tracker.onReceiveLoopStarted(socketId, generation)
        tracker.onFirstOutboundAfterRebind(socketId, generation)
        tracker.onQualificationTimeout()

        val snapshot = tracker.snapshot()
        assertEquals(LinkQualificationState.UNQUALIFIED, snapshot.linkQualification)
        assertTrue(snapshot.qualificationRetryRequested)
        assertEquals(3, stateChanges().size)
        assertTrue(stateChanges().last().contains("newState=UNQUALIFIED"))
        assertTrue(stateChanges().last().contains("reason=QUALIFICATION_TIMEOUT"))
    }

    @Test
    fun ut3_timeout_invokesRepairHandoffCallback() {
        val tracker = trackerWithoutScheduler()
        val handoffs = mutableListOf<Pair<Long, Long>>()
        tracker.onQualificationRepairHandoff = { socketId, generation -> handoffs.add(socketId to generation) }

        tracker.onSocketBound(4L, 4L, "104")
        tracker.onReceiveLoopStarted(4L, 4L)
        tracker.onFirstOutboundAfterRebind(4L, 4L)
        tracker.onQualificationTimeout()

        assertEquals(listOf(4L to 4L), handoffs)
    }

    @Test
    fun ut4_enterQualificationRepairing_fromUnqualified() {
        val tracker = trackerWithoutScheduler()
        tracker.onSocketBound(4L, 4L, "104")
        tracker.onReceiveLoopStarted(4L, 4L)
        tracker.onFirstOutboundAfterRebind(4L, 4L)
        tracker.onQualificationTimeout()
        tracker.enterQualificationRepairing()

        assertEquals(LinkQualificationState.QUALIFICATION_REPAIRING, tracker.snapshot().linkQualification)
    }

    @Test
    fun ut5_enterUnqualifiedStable_blocksFurtherTimeout() {
        val tracker = trackerWithoutScheduler()
        val handoffs = mutableListOf<Pair<Long, Long>>()
        tracker.onQualificationRepairHandoff = { socketId, generation -> handoffs.add(socketId to generation) }

        tracker.onSocketBound(4L, 4L, "104")
        tracker.enterUnqualifiedStable()
        tracker.onQualificationTimeout()

        assertEquals(LinkQualificationState.UNQUALIFIED_STABLE, tracker.snapshot().linkQualification)
        assertTrue(handoffs.isEmpty())
    }

    @Test
    fun soakSuccess_71f7c454_reachesBidirectionalReady() {
        val tracker = trackerWithoutScheduler()
        val socketId = 3L
        val generation = 2L
        val networkId = "104"

        tracker.onSocketBound(socketId, generation, networkId)
        assertEquals(LinkQualificationState.BOUND, tracker.snapshot().linkQualification)

        tracker.onReceiveLoopStarted(socketId, generation)
        assertEquals(LinkQualificationState.RECEIVE_READY, tracker.snapshot().linkQualification)

        tracker.onFirstOutboundAfterRebind(socketId, generation)
        assertEquals(LinkQualificationState.RECEIVE_READY, tracker.snapshot().linkQualification)
        assertTrue(tracker.snapshot().hasOutboundAfterRebind)
        assertFalse(tracker.snapshot().hasInboundAfterRebind)

        tracker.onFirstInboundAfterRebind(socketId, generation)
        val snapshot = tracker.snapshot()
        assertEquals(LinkQualificationState.BIDIRECTIONAL_READY, snapshot.linkQualification)
        assertTrue(snapshot.hasOutboundAfterRebind)
        assertTrue(snapshot.hasInboundAfterRebind)
        assertFalse(snapshot.qualificationRetryRequested)
    }

    @Test
    fun soakFailure_467cc536_outboundOnlyThenUnqualified() {
        val tracker = trackerWithoutScheduler()
        val socketId = 4L
        val generation = 4L
        val networkId = "104"

        tracker.onSocketBound(socketId, generation, networkId)
        tracker.onReceiveLoopStarted(socketId, generation)
        tracker.onFirstOutboundAfterRebind(socketId, generation)

        assertEquals(LinkQualificationState.RECEIVE_READY, tracker.snapshot().linkQualification)
        assertTrue(tracker.snapshot().hasOutboundAfterRebind)
        assertFalse(tracker.snapshot().hasInboundAfterRebind)

        tracker.onQualificationTimeout()

        val snapshot = tracker.snapshot()
        assertEquals(LinkQualificationState.UNQUALIFIED, snapshot.linkQualification)
        assertTrue(snapshot.qualificationRetryRequested)
    }

    @Test
    fun scheduledTimeout_promotesToUnqualifiedAfterOutboundOnly() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val tracker = LinkQualificationTracker(inboundTimeoutMs = 50L, scheduler = scheduler)
        try {
            tracker.onSocketBound(5L, 1L, "104")
            tracker.onReceiveLoopStarted(5L, 1L)
            tracker.onFirstOutboundAfterRebind(5L, 1L)

            Thread.sleep(120L)

            assertEquals(LinkQualificationState.UNQUALIFIED, tracker.snapshot().linkQualification)
            assertTrue(tracker.snapshot().qualificationRetryRequested)
        } finally {
            scheduler.shutdownNow()
            scheduler.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    @Test
    fun staleFacts_ignoredForDifferentEpoch() {
        val tracker = trackerWithoutScheduler()
        tracker.onSocketBound(1L, 1L, "104")
        tracker.onReceiveLoopStarted(1L, 1L)

        tracker.onSocketBound(2L, 2L, "104")
        tracker.onFirstOutboundAfterRebind(1L, 1L)
        tracker.onFirstInboundAfterRebind(1L, 1L)

        assertEquals(LinkQualificationState.BOUND, tracker.snapshot().linkQualification)
        assertFalse(tracker.snapshot().hasOutboundAfterRebind)
        assertFalse(tracker.snapshot().hasInboundAfterRebind)
        assertTrue(lines.any { it.contains("LINK_FACT_RECEIVED") && it.contains("accepted=false") })
    }

    @Test
    fun networkLost_resetsToUnknown() {
        val tracker = trackerWithoutScheduler()
        tracker.onSocketBound(1L, 1L, "104")
        tracker.onReceiveLoopStarted(1L, 1L)
        tracker.onNetworkLost()

        val snapshot = tracker.snapshot()
        assertEquals(LinkQualificationState.UNKNOWN, snapshot.linkQualification)
        assertEquals(0L, snapshot.socketId)
        assertEquals("none", snapshot.networkId)
        assertFalse(snapshot.qualificationRetryRequested)
    }

    @Test
    fun snapshotRead_tracesOnlyOnExplicitRead() {
        val manager = SignalingTransportManager(
            repairCoordinator = QualificationRepairCoordinator(scheduler = null)
        )
        lines.clear()
        manager.linkQualificationFacts().onSocketBound(1L, 1L, "104")
        manager.linkQualificationSnapshot()
        assertFalse(lines.any { it.startsWith("LINK_QUALIFICATION_SNAPSHOT_READ") })

        val snapshot = manager.readLinkQualificationSnapshot("soak_end")
        assertEquals(LinkQualificationState.BOUND, snapshot.linkQualification)
        assertEquals(1, lines.count { it.startsWith("LINK_QUALIFICATION_SNAPSHOT_READ") })
        assertTrue(lines.any { it.contains("caller=soak_end") && it.contains("state=BOUND") })
    }
}
