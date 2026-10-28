package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.Profile01L2PlcTaxonomyObservation
import com.talkback.core.conference.runtime.Profile01L2PlcTaxonomyObservation.PlcClass
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01L2PlcTaxonomyObservationDeskTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        Profile01L2PlcTaxonomyObservation.resetForTest()
        Profile01L2PlcTaxonomyObservation.testLogSink = { lines += it }
    }

    @After
    fun tearDown() {
        Profile01L2PlcTaxonomyObservation.resetForTest()
    }

    @Test
    fun classify_priority_exclusive() {
        assertEquals(
            PlcClass.PLC_BUFFERED_BUT_MISSED_DEADLINE,
            Profile01L2PlcTaxonomyObservation.classify(
                queued = true,
                rejected = true,
                sockOrAdmitSeen = true,
                arrivalToDeadlineMs = 500L,
                nextExpected = 200L,
                frontier = 200L,
                slot = 100L,
                peekPresent = false,
            ),
        )
        assertEquals(
            PlcClass.PLC_REJECTED_BEFORE_BUFFER,
            Profile01L2PlcTaxonomyObservation.classify(
                queued = false,
                rejected = true,
                sockOrAdmitSeen = true,
                arrivalToDeadlineMs = 500L,
                nextExpected = 200L,
                frontier = 200L,
                slot = 100L,
                peekPresent = false,
            ),
        )
        assertEquals(
            PlcClass.PLC_LATE_AT_RX,
            Profile01L2PlcTaxonomyObservation.classify(
                queued = false,
                rejected = false,
                sockOrAdmitSeen = true,
                arrivalToDeadlineMs = MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1L,
                nextExpected = 100L,
                frontier = 90L,
                slot = 100L,
                peekPresent = false,
            ),
        )
        assertEquals(
            PlcClass.PLC_FRONTIER_PASSED,
            Profile01L2PlcTaxonomyObservation.classify(
                queued = false,
                rejected = false,
                sockOrAdmitSeen = true,
                arrivalToDeadlineMs = 10L,
                nextExpected = 101L,
                frontier = 90L,
                slot = 100L,
                peekPresent = false,
            ),
        )
        assertEquals(
            PlcClass.PLC_NETWORK_MISSING,
            Profile01L2PlcTaxonomyObservation.classify(
                queued = false,
                rejected = false,
                sockOrAdmitSeen = false,
                arrivalToDeadlineMs = null,
                nextExpected = 100L,
                frontier = 90L,
                slot = 100L,
                peekPresent = false,
            ),
        )
    }

    @Test
    fun window_emitsTaxonomyHistogram() {
        repeat(Profile01L2PlcTaxonomyObservation.WINDOW) { i ->
            Profile01L2PlcTaxonomyObservation.notePlc(
                sourceIdentity = "M02",
                slot = 1000L + i,
                pullNowMs = 1_000_000L + i,
                nextExpected = 1000L + i,
                frontier = 900L,
                peekPresent = false,
            )
        }
        val line = lines.single { it.startsWith("L2_PLC_TAXONOMY") }
        assertTrue(line.contains("source=M02"))
        assertTrue(line.contains("NETWORK_MISSING=25"))
        assertTrue(line.contains("LATE_AT_RX=0"))
        assertTrue(line.contains("REJECTED_BEFORE_BUFFER=0"))
        assertTrue(line.contains("BUFFERED_BUT_MISSED_DEADLINE=0"))
        assertTrue(line.contains("FRONTIER_PASSED=0"))
    }

    @Test
    fun sockAdmitReject_thenPlc_isRejectedBeforeBuffer() {
        Profile01L2PlcTaxonomyObservation.noteSockRx("M03", sequence = 42, rxWallMs = 5_000L)
        Profile01L2PlcTaxonomyObservation.noteAdmit(
            sourceIdentity = "M03",
            slot = 42L,
            sequence = 42,
            disposition = FrameAdmitDisposition.LATE_FOR_PLAYOUT,
            nowMs = 5_001L,
            mediaTimeMs = 4_000L,
            arrivalMs = 5_000L,
        )
        repeat(Profile01L2PlcTaxonomyObservation.WINDOW) {
            Profile01L2PlcTaxonomyObservation.notePlc(
                sourceIdentity = "M03",
                slot = 42L,
                pullNowMs = 5_100L,
                nextExpected = 42L,
                frontier = 40L,
                peekPresent = false,
            )
        }
        val tax = lines.single { it.startsWith("L2_PLC_TAXONOMY") }
        assertTrue(tax.contains("REJECTED_BEFORE_BUFFER=25"))
        assertTrue(lines.any { it.startsWith("L2_PLC_CLASS") && it.contains("REJECTED_BEFORE_BUFFER") })
    }
}
