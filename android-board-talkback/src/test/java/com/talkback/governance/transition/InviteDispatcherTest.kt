package com.talkback.governance.transition

import org.junit.Assert.assertEquals
import org.junit.Test

class InviteDispatcherTest {
    private var now = 0L

    private fun dispatcher(policy: InviteDispatchPolicy = InviteDispatchPolicy.MEETING_START_DEFAULT) =
        InviteDispatcher(
            policy = policy,
            clock = { now },
            sleeper = { delayMs -> now += delayMs }
        )

    @Test
    fun success_whenAllTargetsSent() {
        val result = dispatcher().dispatch(listOf("A", "B")) { InviteDispatchSendResult.Sent }
        assertEquals(InviteDispatchOutcome.SUCCESS, result.outcome)
        assertEquals(2, result.sentCount)
        assertEquals(2, result.targetCount)
    }

    @Test
    fun nonRetryable_failsImmediately() {
        var calls = 0
        val result = dispatcher().dispatch(listOf("A")) {
            calls++
            InviteDispatchSendResult.Failed(InviteDispatchError.UNKNOWN_ENDPOINT)
        }
        assertEquals(InviteDispatchOutcome.FAILED_NON_RETRYABLE, result.outcome)
        assertEquals(1, calls)
        assertEquals(InviteDispatchError.UNKNOWN_ENDPOINT, result.lastError)
    }

    @Test
    fun retryable_exhaustsWithinDeadline() {
        var calls = 0
        val result = dispatcher().dispatch(listOf("A")) {
            calls++
            InviteDispatchSendResult.Failed(InviteDispatchError.TRANSPORT_NOT_READY)
        }
        assertEquals(InviteDispatchOutcome.FAILED_RETRY_EXHAUSTED, result.outcome)
        assertEquals(4, calls) // initial + 3 retries
        assertEquals(InviteDispatchError.TRANSPORT_NOT_READY, result.lastError)
    }

    @Test
    fun retryable_succeedsOnSecondAttempt() {
        var calls = 0
        val result = dispatcher().dispatch(listOf("A")) {
            calls++
            if (calls == 1) {
                InviteDispatchSendResult.Failed(InviteDispatchError.SIGNALING_RECONNECTING)
            } else {
                InviteDispatchSendResult.Sent
            }
        }
        assertEquals(InviteDispatchOutcome.SUCCESS, result.outcome)
        assertEquals(1, result.sentCount)
        assertEquals(2, calls)
    }
}
