package com.talkback.governance.transition

import com.talkback.governance.capability.Capability
import com.talkback.governance.capability.CapabilityReadiness
import com.talkback.governance.capability.adapter.StubCapabilityProbe
import com.talkback.governance.transition.PolicyRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransitionCoordinatorTest {
    private var now = 1_000L
    private var nextId = 100L

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
    fun begin_assigns_transition_id() {
        val coordinator = coordinator()
        val result = coordinator.beginTransition(TransitionTrigger.MEETING_END, "CH-01")
        assertTrue(result is BeginTransitionResult.Started)
        val started = (result as BeginTransitionResult.Started).record
        assertEquals(TransitionId(100L), started.id)
        assertEquals(TransitionPhase.PREPARING, started.phase)
    }

    @Test
    fun reject_second_begin_while_active() {
        val coordinator = coordinator()
        coordinator.beginTransition(TransitionTrigger.MEETING_END, "CH-01")
        val second = coordinator.beginTransition(TransitionTrigger.MEETING_START, "CH-01")
        assertTrue(second is BeginTransitionResult.Rejected)
    }

    @Test
    fun abort_allows_new_begin() {
        val coordinator = coordinator()
        coordinator.beginTransition(TransitionTrigger.MEETING_END, "CH-01")
        coordinator.abortTransition("CH-01", "test")
        val second = coordinator.beginTransition(TransitionTrigger.MEETING_START, "CH-01")
        assertTrue(second is BeginTransitionResult.Started)
    }

    @Test
    fun timeout_clears_active_slot() {
        val coordinator = coordinator()
        coordinator.beginTransition(TransitionTrigger.MEETING_END, "CH-01")
        now += 12_001L
        val timedOut = coordinator.expireTimeouts("CH-01")
        assertEquals(1, timedOut.size)
        assertEquals(TransitionTerminalState.TIMED_OUT, timedOut.single().terminal)
        assertNull(coordinator.activeTransition("CH-01"))
    }
}
