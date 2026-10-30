package com.talkback.core.conference.capacity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gres3H1d5aAdjudicatorTest {
    @Test
    fun adjudicate_success_9_of_9_valid() {
        val output =
            Gres3H1d5aAdjudicator.adjudicate(
                Gres3EgressWarmupResult(
                    attempted = true,
                    legsSucceeded = 9,
                    legsFailed = 0,
                    legFailures = emptyList(),
                ),
            )
        assertEquals(Gres3EgressWarmupVerdict.VALID, output.verdict)
        assertEquals(Gres3HarnessLifecycleState.MEASUREMENT, output.lifecycleState)
        assertTrue(output.warmEgressSucceeded)
        assertEquals(1, output.warmupAttempts)
        assertTrue(output.reasons.isEmpty())
    }

    @Test
    fun adjudicate_partialFailure_invalid_aborts() {
        val failure =
            Gres3EgressWarmupLegFailure(
                legIndex = 2,
                receiverModuleId = "M02",
                moduleFixedIp = "10.0.0.2",
                mediaPort = 47003,
                endpointKey = "M02@10.0.0.2:47003",
                exceptionClass = "java.io.IOException",
                message = "Network unreachable",
                causeClass = null,
                causeMessage = null,
            )
        val output =
            Gres3H1d5aAdjudicator.adjudicate(
                Gres3EgressWarmupResult(
                    attempted = true,
                    legsSucceeded = 8,
                    legsFailed = 1,
                    legFailures = listOf(failure),
                ),
            )
        assertEquals(Gres3EgressWarmupVerdict.INVALID, output.verdict)
        assertEquals(Gres3HarnessLifecycleState.ABORTED_BEFORE_MEASUREMENT, output.lifecycleState)
        assertFalse(output.warmEgressSucceeded)
        assertEquals(1, output.legFailures.size)
        assertTrue(output.reasons.contains("EGRESS_WARMUP_NOT_9_OF_9"))
        assertTrue(output.reasons.contains("EGRESS_WARMUP_LEG_FAILURES:1"))
    }

    @Test
    fun adjudicate_setupError_invalid() {
        val output =
            Gres3H1d5aAdjudicator.adjudicate(
                Gres3EgressWarmupResult(
                    attempted = true,
                    legsSucceeded = 0,
                    legsFailed = 9,
                    legFailures = emptyList(),
                    setupError = "ArrayIndexOutOfBoundsException: artifact too short",
                ),
            )
        assertEquals(Gres3EgressWarmupVerdict.INVALID, output.verdict)
        assertFalse(output.warmEgressSucceeded)
        assertTrue(output.reasons.any { it.startsWith("EGRESS_WARMUP_SETUP_ERROR:") })
    }
}
