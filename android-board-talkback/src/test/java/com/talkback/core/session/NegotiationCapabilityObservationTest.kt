package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B3.0 INV-NEG-015: observation ledger baseline + rising-edge (not Capability Truth).
 */
class NegotiationCapabilityObservationTest {
    private lateinit var observation: NegotiationCapabilityObservation
    private val sessionId = "sess-obs"
    private val remote = "M20"

    @Before
    fun setUp() {
        observation = NegotiationCapabilityObservation()
    }

    @Test
    fun staleTrue_thenDeferBaseline_thenStable_emitsRisingEdge() {
        // Prior successful recompute left previous=true (soak: CAPABILITY_REEVAL previous=true).
        assertTrue(
            observation.observeRecompute(sessionId, remote, executable = true).risingEdge
        ) // null → true
        assertFalse(
            observation.observeRecompute(sessionId, remote, executable = true).risingEdge
        ) // true → true (stale observation, no wakeup)
        assertEquals(true, observation.lastObserved(sessionId, remote))

        // INV-NEG-015: defer admission seeds false baseline (not via bare probe).
        observation.establishDeferredBaseline(sessionId, remote)
        assertEquals(false, observation.lastObserved(sessionId, remote))

        // STABLE / gate opens → false→true rising edge → CAN_EXECUTE.
        val result = observation.observeRecompute(sessionId, remote, executable = true)
        assertEquals(false, result.previous)
        assertTrue(result.risingEdge)
    }

    @Test
    fun trueToTrue_isNotRisingEdge() {
        observation.observeRecompute(sessionId, remote, executable = true)
        val again = observation.observeRecompute(sessionId, remote, executable = true)
        assertEquals(true, again.previous)
        assertFalse(again.risingEdge)
    }

    @Test
    fun clearSession_removesObservation() {
        observation.observeRecompute(sessionId, remote, executable = true)
        observation.clearSession(sessionId)
        assertNull(observation.lastObserved(sessionId, remote))
        // After clear, first true is rising (null→true).
        assertTrue(observation.observeRecompute(sessionId, remote, executable = true).risingEdge)
    }
}
