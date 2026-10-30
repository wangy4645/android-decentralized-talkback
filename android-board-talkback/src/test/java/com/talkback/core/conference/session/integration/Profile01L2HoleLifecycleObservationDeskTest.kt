package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.Profile01L2HoleLifecycleObservation
import com.talkback.core.conference.runtime.Profile01L2HoleLifecycleObservation.HoleFate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01L2HoleLifecycleObservationDeskTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        Profile01L2HoleLifecycleObservation.resetForTest()
        Profile01L2HoleLifecycleObservation.testLogSink = { lines += it }
    }

    @After
    fun tearDown() {
        Profile01L2HoleLifecycleObservation.resetForTest()
    }

    @Test
    fun neverAtSocket_plcConsumesHole() {
        Profile01L2HoleLifecycleObservation.noteHoleOpen("M02", 100L, nowMs = 1_000L)
        Profile01L2HoleLifecycleObservation.notePullPlc("M02", 100L, nowMs = 1_040L)
        val life = lines.single { it.startsWith("L2_HOLE_LIFE ") }
        assertTrue(life.contains("fate=${HoleFate.NEVER_AT_SOCKET.name}"))
        assertTrue(life.contains("lifeMs=40"))
        assertTrue(life.contains("sock=0"))
    }

    @Test
    fun arrivedRejected_reorderWhileHoleOpen() {
        Profile01L2HoleLifecycleObservation.noteHoleOpen("M03", 200L, nowMs = 2_000L)
        Profile01L2HoleLifecycleObservation.noteAdmit(
            sourceIdentity = "M03",
            slot = 210L,
            disposition = FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED,
            nowMs = 2_010L,
            nextExpectedBefore = 200L,
        )
        Profile01L2HoleLifecycleObservation.noteAdmit(
            sourceIdentity = "M03",
            slot = 200L,
            disposition = FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED,
            nowMs = 2_020L,
            nextExpectedBefore = 200L,
        )
        Profile01L2HoleLifecycleObservation.notePullPlc("M03", 200L, nowMs = 2_050L)
        val life = lines.single { it.startsWith("L2_HOLE_LIFE ") }
        assertTrue(life.contains("fate=${HoleFate.ARRIVED_REJECTED.name}"))
        assertTrue(life.contains("rejectKind=REORDER"))
        assertTrue(life.contains("maxDisp=10"))
        assertTrue(life.contains("futurePkts="))
    }

    @Test
    fun window_emitsNeverVsRejectedSplit() {
        repeat(20) { i ->
            val slot = 1000L + i
            Profile01L2HoleLifecycleObservation.noteHoleOpen("M01", slot, nowMs = 5_000L + i)
            Profile01L2HoleLifecycleObservation.notePullPlc("M01", slot, nowMs = 5_020L + i)
        }
        repeat(5) { i ->
            val slot = 2000L + i
            Profile01L2HoleLifecycleObservation.noteHoleOpen("M01", slot, nowMs = 6_000L + i)
            Profile01L2HoleLifecycleObservation.noteSockRx("M01", sequence = slot.toInt(), rxWallMs = 6_005L + i)
            Profile01L2HoleLifecycleObservation.noteAdmit(
                sourceIdentity = "M01",
                slot = slot,
                disposition = FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED,
                nowMs = 6_006L + i,
                nextExpectedBefore = slot,
            )
            Profile01L2HoleLifecycleObservation.notePullPlc("M01", slot, nowMs = 6_030L + i)
        }
        val win = lines.single { it.startsWith("L2_HOLE_LIFE_WINDOW") }
        assertTrue(win.contains("NEVER_AT_SOCKET=20"))
        assertTrue(win.contains("ARRIVED_REJECTED=5"))
        assertEquals(1, lines.count { it.startsWith("L2_HOLE_LIFE_WINDOW") })
    }

    @Test
    fun filledReal_closesOpenHole() {
        Profile01L2HoleLifecycleObservation.noteHoleOpen("M04", 50L, nowMs = 100L)
        Profile01L2HoleLifecycleObservation.noteSockRx("M04", 50, rxWallMs = 110L)
        Profile01L2HoleLifecycleObservation.noteAdmit(
            sourceIdentity = "M04",
            slot = 50L,
            disposition = FrameAdmitDisposition.QUEUED,
            nowMs = 111L,
            nextExpectedBefore = 50L,
        )
        Profile01L2HoleLifecycleObservation.notePullReal("M04", 50L, nowMs = 120L)
        val life = lines.single { it.startsWith("L2_HOLE_LIFE ") }
        assertTrue(life.contains("fate=${HoleFate.FILLED_REAL.name}"))
        assertTrue(life.contains("lifeMs=20"))
    }
}
