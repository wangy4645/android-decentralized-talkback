package com.talkback.core.signaling.link

import com.talkback.core.signaling.SignalingTransportManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QualificationRepairCoordinatorTest {

    private val lines = mutableListOf<String>()
    private lateinit var tracker: LinkQualificationTracker
    private lateinit var coordinator: QualificationRepairCoordinator
    private val rebindCount = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        rebindCount.clear()
        LinkQualificationTrace.resetForTest { lines.add(it) }
        tracker = LinkQualificationTracker(scheduler = null)
        coordinator = QualificationRepairCoordinator(scheduler = null)
        coordinator.tracker = tracker
        coordinator.currentNetworkId = { "104" }
        coordinator.rebindSignaling = { _, reason -> rebindCount.add(reason) }
        tracker.onQualificationRepairHandoff = { socketId, generation ->
            coordinator.onQualificationTimeout(socketId, generation)
        }
        tracker.onQualificationStateChanged = { oldState, newState ->
            coordinator.onQualificationStateChanged(oldState, newState)
        }
    }

    @After
    fun tearDown() {
        LinkQualificationTrace.resetForTest(null)
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
    fun l4_1_duplicateTimeout_sameGeneration_rejected() {
        seedOutboundOnly()
        tracker.onQualificationTimeout()
        lines.clear()
        coordinator.onQualificationTimeout(4L, 4L)

        assertTrue(lines.any { it.startsWith("LINK_QUALIFICATION_REPAIR_DUPLICATE_REJECTED") })
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
}
