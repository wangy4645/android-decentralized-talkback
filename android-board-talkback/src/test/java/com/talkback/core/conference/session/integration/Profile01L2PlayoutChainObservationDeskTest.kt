package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.Profile01L2PlayoutChainObservation
import com.talkback.core.conference.runtime.Profile01L2PlayoutChainObservation.PullKind
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01L2PlayoutChainObservationDeskTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        Profile01L2PlayoutChainObservation.resetForTest()
        Profile01L2PlayoutChainObservation.testLogSink = { lines += it }
    }

    @After
    fun tearDown() {
        Profile01L2PlayoutChainObservation.resetForTest()
    }

    @Test
    fun admitOkWindow_emitsArrivalToDeadline() {
        repeat(Profile01L2PlayoutChainObservation.WINDOW) { i ->
            Profile01L2PlayoutChainObservation.noteAdmitOk(
                sourceIdentity = "M02",
                slot = 100L + i,
                nextExpectedAfter = 100L + i,
                latestBuffered = 100L + i,
                frontier = 90L,
                jitterSize = 3,
                arrivalToDeadlineMs = 10L + i,
                dispVsNext = 0L,
            )
        }
        val line = lines.single { it.startsWith("L2_ADMIT_OK") }
        assertTrue(line.contains("source=M02"))
        assertTrue(line.contains("arrivalToDeadlineMinMs=10"))
        assertTrue(line.contains("arrivalToDeadlineMaxMs=34"))
        assertTrue(line.contains("queued=25"))
    }

    @Test
    fun pullWindow_emitsRealPlcEmptyStreaks() {
        repeat(10) {
            Profile01L2PlayoutChainObservation.notePull(
                sourceIdentity = "M03",
                slot = 50L + it,
                kind = PullKind.REAL,
                nextExpected = 50L + it,
                jitterContains = true,
            )
        }
        repeat(5) {
            Profile01L2PlayoutChainObservation.notePull(
                sourceIdentity = "M03",
                slot = 60L + it,
                kind = PullKind.PLC,
                nextExpected = 60L + it,
                jitterContains = false,
            )
        }
        repeat(10) {
            Profile01L2PlayoutChainObservation.notePull(
                sourceIdentity = "M03",
                slot = 70L + it,
                kind = PullKind.EMPTY,
                nextExpected = 70L + it,
                jitterContains = false,
            )
        }
        val line = lines.single { it.startsWith("L2_PULL") }
        assertTrue(line.contains("real=10"))
        assertTrue(line.contains("plc=5"))
        assertTrue(line.contains("empty=10"))
        assertTrue(line.contains("plcStreakMax=5"))
        assertTrue(line.contains("emptyStreakMax=10"))
    }
}