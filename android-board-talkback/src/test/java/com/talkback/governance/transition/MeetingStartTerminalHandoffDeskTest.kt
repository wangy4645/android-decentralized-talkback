package com.talkback.governance.transition

import com.talkback.governance.capability.Capability
import com.talkback.governance.capability.CapabilityReadiness
import com.talkback.governance.capability.adapter.StubCapabilityProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeetingStartTerminalHandoffDeskTest {
    private var now = 1_000L
    private var nextId = 200L

    @Before
    fun setUp() {
        PolicyRegistry.resetForTests()
        PolicyRegistry.ensureValidated()
    }

    private fun coordinator(): TransitionCoordinator = TransitionCoordinator(
        probes = listOf(
            StubCapabilityProbe(Capability.Routing) { CapabilityReadiness.READY }
        ),
        clock = { now },
        idSource = { nextId++ }
    )

    @Test
    fun failStart_thenMeetingEndCanBegin() {
        val coordinator = coordinator()
        coordinator.beginTransition(TransitionTrigger.MEETING_START, "CH-01")
        val failed = coordinator.failTransition("CH-01", "CONFERENCE_TERMINATED")
        assertEquals(TransitionTerminalState.FAILED, failed?.terminal)
        assertEquals("CONFERENCE_TERMINATED", failed?.abortReason)
        assertNull(coordinator.activeTransition("CH-01"))

        val end = coordinator.beginTransition(TransitionTrigger.MEETING_END, "CH-01")
        assertTrue(end is BeginTransitionResult.Started)
        assertEquals(
            TransitionTrigger.MEETING_END,
            (end as BeginTransitionResult.Started).record.trigger
        )
    }

    @Test
    fun failStart_isIdempotent() {
        val coordinator = coordinator()
        coordinator.beginTransition(TransitionTrigger.MEETING_START, "CH-01")
        coordinator.failTransition("CH-01", "CONFERENCE_TERMINATED")
        assertNull(coordinator.failTransition("CH-01", "CONFERENCE_TERMINATED"))
        assertNull(coordinator.activeTransition("CH-01"))
    }

    @Test
    fun livingStart_notClearedBeforeTimeout() {
        val coordinator = coordinator()
        coordinator.beginTransition(TransitionTrigger.MEETING_START, "CH-01")
        now += 5_000L
        assertEquals(
            TransitionTrigger.MEETING_START,
            coordinator.activeTransition("CH-01")?.trigger
        )
    }
}
