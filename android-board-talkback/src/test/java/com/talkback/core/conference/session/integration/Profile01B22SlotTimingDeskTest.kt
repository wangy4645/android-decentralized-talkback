package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.Profile01B22SlotTimingObservation
import com.talkback.core.conference.runtime.Profile01B22SlotTimingObservation.PlcCause
import com.talkback.core.conference.runtime.Profile01B22SlotTimingObservation.RejectEvent
import com.talkback.core.conference.runtime.Profile01B22SlotTimingObservation.RejectKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01B22SlotTimingDeskTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        Profile01B22SlotTimingObservation.resetForTest()
        Profile01B22SlotTimingObservation.testLogSink = { lines += it }
    }

    @After
    fun tearDown() {
        Profile01B22SlotTimingObservation.resetForTest()
    }

    @Test
    fun classify_reorderHole_vs_late_vs_unseen() {
        val reorder =
            RejectEvent(
                kind = RejectKind.REORDER,
                slot = 100L,
                nextExpected = 90L,
                frontier = 90L,
                displacement = 10L,
                latenessMs = 5L,
                nowMs = 1_000L,
            )
        val late =
            RejectEvent(
                kind = RejectKind.LATE,
                slot = 50L,
                nextExpected = 60L,
                frontier = 70L,
                displacement = -10L,
                latenessMs = 200L,
                nowMs = 2_000L,
            )
        assertEquals(
            PlcCause.REORDER_HOLE,
            Profile01B22SlotTimingObservation.classifyPlcSlot(90L, listOf(reorder)),
        )
        assertEquals(
            PlcCause.REORDER_HOLE,
            Profile01B22SlotTimingObservation.classifyPlcSlot(99L, listOf(reorder)),
        )
        assertEquals(
            PlcCause.UNSEEN,
            Profile01B22SlotTimingObservation.classifyPlcSlot(100L, listOf(reorder)),
        )
        assertEquals(
            PlcCause.LATE,
            Profile01B22SlotTimingObservation.classifyPlcSlot(50L, listOf(late)),
        )
        assertEquals(
            PlcCause.UNSEEN,
            Profile01B22SlotTimingObservation.classifyPlcSlot(51L, listOf(late)),
        )
    }

    @Test
    fun rejectWindow_emitsDisplacement() {
        repeat(Profile01B22SlotTimingObservation.WINDOW) { i ->
            Profile01B22SlotTimingObservation.noteAdmitReject(
                sourceIdentity = "M01",
                kind = RejectKind.REORDER,
                slot = 200L + i,
                nextExpected = 190L,
                frontier = 190L,
                nowMs = 10_000L + i,
                mediaTimeMs = 9_980L,
                jitterSize = 2,
            )
        }
        val line = lines.single { it.startsWith("B22_ADMIT_REJECT") }
        assertTrue(line.contains("source=M01"))
        assertTrue(line.contains("reorder=25"))
        assertTrue(line.contains("late=0"))
        assertTrue(line.contains("dispMin=10"))
        assertTrue(line.contains("dispMax=34"))
    }

    @Test
    fun plcWindow_countsReorderHole() {
        Profile01B22SlotTimingObservation.noteAdmitReject(
            sourceIdentity = "M02",
            kind = RejectKind.REORDER,
            slot = 100L,
            nextExpected = 90L,
            frontier = 90L,
            nowMs = 1_000L,
            mediaTimeMs = 1_000L,
            jitterSize = 1,
        )
        repeat(Profile01B22SlotTimingObservation.WINDOW) { i ->
            Profile01B22SlotTimingObservation.notePlcSlot("M02", 90L + (i % 10))
        }
        val line = lines.single { it.startsWith("B22_PLC_CAUSE") }
        assertTrue(line.contains("reorderHole=25"))
        assertTrue(line.contains("unseen=0"))
    }
}