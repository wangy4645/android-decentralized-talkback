package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.Profile01B20PlcReasonObservation
import com.talkback.core.conference.runtime.Profile01B20PlcReasonObservation.PlcPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01B20PlcReasonDeskTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        Profile01B20PlcReasonObservation.resetForTest()
        Profile01B20PlcReasonObservation.testLogSink = { lines += it }
    }

    @After
    fun tearDown() {
        Profile01B20PlcReasonObservation.resetForTest()
    }

    @Test
    fun classify_mapsAbc() {
        assertEquals('A', Profile01B20PlcReasonObservation.classify(ingressSeen = false, peekPresent = false))
        assertEquals('B', Profile01B20PlcReasonObservation.classify(ingressSeen = true, peekPresent = false))
        assertEquals('C', Profile01B20PlcReasonObservation.classify(ingressSeen = true, peekPresent = true))
        assertEquals('C', Profile01B20PlcReasonObservation.classify(ingressSeen = false, peekPresent = true))
    }

    @Test
    fun window_emitsHistogramAfterTwentyFivePlc() {
        repeat(Profile01B20PlcReasonObservation.WINDOW) { i ->
            Profile01B20PlcReasonObservation.notePlc(
                sourceIdentity = "M04",
                slot = i.toLong(),
                path = PlcPath.EMPTY_DEADLINE,
                peekPresent = false,
                jitterSize = 0,
            )
        }
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line.contains("B20_PLC_REASON source=M04"))
        assertTrue(line.contains("A=25"))
        assertTrue(line.contains("B=0"))
        assertTrue(line.contains("C=0"))
        assertTrue(line.contains("pathEmptyDeadline=25"))
    }

    @Test
    fun ingressThenPlc_isClassB() {
        Profile01B20PlcReasonObservation.noteIngress("M02", 9L)
        repeat(Profile01B20PlcReasonObservation.WINDOW) {
            Profile01B20PlcReasonObservation.notePlc(
                sourceIdentity = "M02",
                slot = 9L,
                path = PlcPath.HOLE,
                peekPresent = false,
                jitterSize = 1,
            )
        }
        val line = lines.single()
        assertTrue(line.contains("B=25"))
        assertTrue(line.contains("pathHole=25"))
        assertTrue(line.contains("lastClass=B"))
    }
}