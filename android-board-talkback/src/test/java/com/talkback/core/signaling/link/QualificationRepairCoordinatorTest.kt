package com.talkback.core.signaling.link

import com.talkback.core.signaling.SignalingTransportManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Delayed
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class QualificationRepairCoordinatorTest {

    private val lines = mutableListOf<String>()
    private lateinit var tracker: LinkQualificationTracker
    private lateinit var coordinator: QualificationRepairCoordinator
    private val rebindCount = mutableListOf<String>()
    private var capturingScheduler: CapturingScheduler? = null

    @Before
    fun setUp() {
        lines.clear()
        rebindCount.clear()
        QualificationRepairCoordinator.admitNextAttemptWhileQualificationWait = true
        LinkQualificationTrace.resetForTest { lines.add(it) }
        tracker = LinkQualificationTracker(scheduler = null)
        coordinator = QualificationRepairCoordinator(scheduler = null)
        wireCoordinator(coordinator)
    }

    @After
    fun tearDown() {
        LinkQualificationTrace.resetForTest(null)
        capturingScheduler?.shutdownQuietly()
        capturingScheduler = null
        QualificationRepairCoordinator.admitNextAttemptWhileQualificationWait = true
    }

    private fun wireCoordinator(target: QualificationRepairCoordinator) {
        target.tracker = tracker
        target.currentNetworkId = { "104" }
        target.rebindSignaling = { _, reason -> rebindCount.add(reason) }
        tracker.onQualificationRepairHandoff = { socketId, generation ->
            target.onQualificationTimeout(socketId, generation)
        }
        tracker.onQualificationStateChanged = { oldState, newState ->
            target.onQualificationStateChanged(oldState, newState)
        }
    }

    private fun seedOutboundOnly(socketId: Long = 4L, generation: Long = 4L) {
        tracker.onSocketBound(socketId, generation, "104")
        tracker.onReceiveLoopStarted(socketId, generation)
        tracker.onFirstOutboundAfterRebind(socketId, generation)
    }

    @Test
    fun l4_1_timeout_emitsRepairRequestedAndStartsRebind() {
        seedOutboundOnly()
        tracker.onQualificationTimeout()

        assertEquals(1, rebindCount.size)
        assertEquals("qualification_repair", rebindCount.single())
        assertTrue(lines.any { it.startsWith("LINK_QUALIFICATION_REPAIR_REQUESTED") })
        assertTrue(lines.any { it.startsWith("LINK_QUALIFICATION_REPAIR_STARTED") })
        assertEquals(1, coordinator.repairAttemptForTest())
    }

    @Test
    fun l15_qualificationWait_timeout_admitsNextAttempt() {
        seedOutboundOnly()
        tracker.onQualificationTimeout()
        assertEquals(1, rebindCount.size)
        assertEquals(1, coordinator.repairAttemptForTest())
        assertEquals(TransportRepairState.QUALIFICATION_WAIT, coordinator.repairStateForTest())
        lines.clear()

        // L.1.5: second QUALIFICATION_TIMEOUT while WAIT admits attempt 2 (not duplicate reject)
        coordinator.onQualificationTimeout(4L, 4L)

        assertEquals(2, rebindCount.size)
        assertEquals(2, coordinator.repairAttemptForTest())
        assertTrue(lines.any { it.startsWith("LINK_QUALIFICATION_REPAIR_REQUESTED") && it.contains("repairAttempt=2") })
        assertTrue(lines.any { it.startsWith("LINK_QUALIFICATION_REPAIR_STARTED") })
        assertTrue(lines.none { it.startsWith("LINK_QUALIFICATION_REPAIR_DUPLICATE_REJECTED") })
    }

    @Test
    fun l15_qualificationWait_timeout_rollbackFlag_rejects() {
        coordinator.admitNextAttemptWhileQualificationWait = false
        seedOutboundOnly()
        tracker.onQualificationTimeout()
        lines.clear()
        coordinator.onQualificationTimeout(4L, 4L)

        assertTrue(lines.any { it.startsWith("LINK_QUALIFICATION_REPAIR_DUPLICATE_REJECTED") })
        assertEquals(1, rebindCount.size)
        assertEquals(1, coordinator.repairAttemptForTest())
    }

    @Test
    fun l4_1_duplicateWhileRequested_sameGeneration_rejected() {
        val pending = mutableListOf<Runnable>()
        capturingScheduler = CapturingScheduler(pending)
        coordinator = QualificationRepairCoordinator(scheduler = capturingScheduler)
        wireCoordinator(coordinator)

        seedOutboundOnly()
        tracker.onQualificationTimeout()
        assertEquals(1, pending.size)
        assertEquals(TransportRepairState.REPAIR_REQUESTED, coordinator.repairStateForTest())
        lines.clear()

        coordinator.onQualificationTimeout(4L, 4L)
        assertTrue(lines.any { it.startsWith("LINK_QUALIFICATION_REPAIR_DUPLICATE_REJECTED") })
        assertEquals(0, rebindCount.size)

        pending.single().run()
        assertEquals(1, rebindCount.size)
    }

    @Test
    fun l4_3_capExhausted_becomesUnqualifiedStable() {
        for (generation in 1L..3L) {
            seedOutboundOnly(socketId = generation + 3, generation = generation)
            tracker.onQualificationTimeout()
        }
        seedOutboundOnly(socketId = 99L, generation = 99L)
        tracker.onQualificationTimeout()

        assertEquals(LinkQualificationState.UNQUALIFIED_STABLE, tracker.snapshot().linkQualification)
        assertEquals(TransportRepairState.REPAIR_EXHAUSTED, coordinator.repairStateForTest())
        assertTrue(lines.any { it.startsWith("LINK_QUALIFICATION_REPAIR_EXHAUSTED") })
        assertEquals(3, rebindCount.size)
    }

    @Test
    fun l4_2_repairSuccess_resetsCoordinator() {
        seedOutboundOnly()
        tracker.onQualificationTimeout()
        tracker.onSocketBound(5L, 5L, "104")
        tracker.onReceiveLoopStarted(5L, 5L)
        tracker.onFirstOutboundAfterRebind(5L, 5L)
        tracker.onFirstInboundAfterRebind(5L, 5L)

        assertEquals(LinkQualificationState.BIDIRECTIONAL_READY, tracker.snapshot().linkQualification)
        assertEquals(TransportRepairState.IDLE, coordinator.repairStateForTest())
        assertEquals(0, coordinator.repairAttemptForTest())
        assertTrue(lines.any { it.startsWith("LINK_QUALIFICATION_REPAIR_SUCCEEDED") })
    }

    @Test
    fun l4_repairingState_visibleInSnapshot() {
        val manager = SignalingTransportManager(
            repairCoordinator = QualificationRepairCoordinator(scheduler = null)
        )
        val facts = manager.linkQualificationFacts()
        facts.onSocketBound(4L, 4L, "104")
        facts.onReceiveLoopStarted(4L, 4L)
        facts.onFirstOutboundAfterRebind(4L, 4L)
        facts.onQualificationTimeout()

        val snapshot = manager.linkQualificationSnapshot()
        assertEquals(1, snapshot.repairAttempt)
        assertEquals(TransportRepairState.QUALIFICATION_WAIT, snapshot.transportRepairState)
    }

    /** Captures schedule() runnables without executing them (for REQUESTED-window duplicate tests). */
    private class CapturingScheduler(
        private val pending: MutableList<Runnable>,
        private val delegate: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "test-repair-capture").apply { isDaemon = true }
        }
    ) : ScheduledExecutorService by delegate {
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            pending.add(command)
            return object : ScheduledFuture<Unit> {
                override fun cancel(mayInterruptIfRunning: Boolean): Boolean = true
                override fun isCancelled(): Boolean = false
                override fun isDone(): Boolean = false
                override fun get(): Unit = Unit
                override fun get(timeout: Long, unit: TimeUnit): Unit = Unit
                override fun getDelay(unit: TimeUnit): Long = delay
                override fun compareTo(other: Delayed): Int = 0
            }
        }

        fun shutdownQuietly() {
            delegate.shutdownNow()
        }
    }
}
