package com.talkback.core.conference.session.integration

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01B25SeqPresenceDeskTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        Profile01B25SeqPresenceObservation.resetForTest()
        Profile01B25SeqPresenceObservation.testLogSink = { lines += it }
    }

    @After
    fun tearDown() {
        Profile01B25SeqPresenceObservation.resetForTest()
    }

    @Test
    fun classify_seenVsNever() {
        assertEquals(
            Profile01B25SeqPresenceObservation.PlcSeqClass.SEEN_AT_RX,
            Profile01B25SeqPresenceObservation.classifyPlcSeq(20_480L, listOf(20_480)),
        )
        assertEquals(
            Profile01B25SeqPresenceObservation.PlcSeqClass.NEVER_AT_RX,
            Profile01B25SeqPresenceObservation.classifyPlcSeq(20_481L, listOf(20_480)),
        )
    }

    @Test
    fun txWindow_countsGapWhenSeqSkips() {
        Profile01B25SeqPresenceObservation.noteTxSent("M01", 100)
        repeat(24) { i ->
            Profile01B25SeqPresenceObservation.noteTxSent("M01", 101 + i)
        }
        val contig = lines.single { it.startsWith("B25_TX_SEQ") }
        assertTrue(contig.contains("gaps=0"))
        lines.clear()
        Profile01B25SeqPresenceObservation.noteTxSent("M01", 200)
        repeat(24) { i ->
            Profile01B25SeqPresenceObservation.noteTxSent("M01", 201 + i)
        }
        val gapped = lines.single { it.startsWith("B25_TX_SEQ") }
        assertTrue(gapped.contains("gaps=1"))
    }

    @Test
    fun plcWindow_neverAtRxWhenSeqAbsent() {
        Profile01B25SeqPresenceObservation.noteRxSeq("M02", 10)
        repeat(Profile01B25SeqPresenceObservation.WINDOW) {
            Profile01B25SeqPresenceObservation.notePlcSlot("M02", 99L)
        }
        val line = lines.single { it.startsWith("B25_PLC_SEQ") }
        assertTrue(line.contains("neverAtRx=25"))
        assertTrue(line.contains("seenAtRx=0"))
    }

    @Test
    fun plcWindow_seenAtRxWhenSeqPresent() {
        Profile01B25SeqPresenceObservation.noteRxSeq("M03", 50)
        repeat(Profile01B25SeqPresenceObservation.WINDOW) {
            Profile01B25SeqPresenceObservation.notePlcSlot("M03", 50L)
        }
        val line = lines.single { it.startsWith("B25_PLC_SEQ") }
        assertTrue(line.contains("seenAtRx=25"))
        assertTrue(line.contains("neverAtRx=0"))
    }
}