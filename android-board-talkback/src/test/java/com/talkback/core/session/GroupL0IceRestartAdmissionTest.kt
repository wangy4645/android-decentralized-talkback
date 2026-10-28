package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupL0IceRestartAdmissionTest {
    @Test
    fun blocksWhenProbeNotExecutable() {
        val decision = GroupL0IceRestartAdmission.evaluate(
            probeExecutable = false,
            iceConnectionState = "CHECKING",
            checkingAgeMs = 30_000L,
            timeoutMs = 20_000L,
        )
        assertEquals(GroupL0IceRestartAdmission.Decision.BLOCK_GATE, decision)
    }

    @Test
    fun blocksCheckingUntilThisEdgeTimeoutElapses() {
        val tooNew = GroupL0IceRestartAdmission.evaluate(
            probeExecutable = true,
            iceConnectionState = "CHECKING",
            checkingAgeMs = 0L,
            timeoutMs = 20_000L,
        )
        assertEquals(GroupL0IceRestartAdmission.Decision.BLOCK_CHECKING_WINDOW, tooNew)

        val elapsed = GroupL0IceRestartAdmission.evaluate(
            probeExecutable = true,
            iceConnectionState = "CHECKING",
            checkingAgeMs = 20_000L,
            timeoutMs = 20_000L,
        )
        assertEquals(GroupL0IceRestartAdmission.Decision.ALLOW, elapsed)
    }

    @Test
    fun failedIceDoesNotUseCheckingWindow() {
        val decision = GroupL0IceRestartAdmission.evaluate(
            probeExecutable = true,
            iceConnectionState = "FAILED",
            checkingAgeMs = 0L,
            timeoutMs = 20_000L,
        )
        assertEquals(GroupL0IceRestartAdmission.Decision.ALLOW, decision)
    }
}
