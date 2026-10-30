package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferenceRealizationLineageTest {

    @Test
    fun parseRawSdp_isUnknown() {
        val parsed = ConferenceRealizationLineage.parseAnswer("v=0\r\no=x")
        assertEquals("v=0\r\no=x", parsed.sdp)
        assertEquals(ConferenceRealizationLineage.UNKNOWN, parsed.offerLineageId)
        assertEquals(
            ConferenceRealizationLineage.Correlation.OFFER_LINEAGE_UNKNOWN,
            ConferenceRealizationLineage.correlate(parsed.offerLineageId, "CR1")
        )
    }

    @Test
    fun encodeThenParse_preservesLineage() {
        val wire = ConferenceRealizationLineage.encodeAnswer(
            sdp = "v=0\noffer",
            offerLineageId = "CR9",
            realizationAttemptId = "RA2"
        )
        val parsed = ConferenceRealizationLineage.parseAnswer(wire)
        assertEquals("v=0\noffer", parsed.sdp)
        assertEquals("CR9", parsed.offerLineageId)
        assertEquals("RA2", parsed.realizationAttemptId)
        assertEquals(
            ConferenceRealizationLineage.Correlation.MATCH,
            ConferenceRealizationLineage.correlate(parsed.offerLineageId, "CR9")
        )
        assertEquals(
            ConferenceRealizationLineage.Correlation.MISMATCH,
            ConferenceRealizationLineage.correlate(parsed.offerLineageId, "CR8")
        )
    }

    @Test
    fun encodeWithoutLineage_leavesRawSdp() {
        val wire = ConferenceRealizationLineage.encodeAnswer("v=0", null, null)
        assertEquals("v=0", wire)
    }

    @Test
    fun formatEvent_includesRequiredIds() {
        val line = ConferenceRealizationLineage.formatEvent(
            stage = "CREATE_OFFER",
            sessionId = "sess",
            remoteModuleId = "M03",
            offerLineageId = "CR1",
            realizationAttemptId = "RA1",
            pcGeneration = 73L,
            pcHash = 84622929
        )
        assertTrue(line.startsWith("CONFERENCE_OFFER_LINEAGE stage=CREATE_OFFER"))
        assertTrue(line.contains("remote=M03"))
        assertTrue(line.contains("offerLineageId=CR1"))
        assertTrue(line.contains("realizationAttemptId=RA1"))
        assertTrue(line.contains("pcGeneration=73"))
        assertTrue(line.contains("pcHash=84622929"))
    }

    @Test
    fun pendingDrain_encodesRequestOrder() {
        val items = listOf(
            "GROUP" to "releaseInFlight",
            "CONFERENCE" to "requestEngine",
            "CONFERENCE" to "releaseInFlight"
        )
        val encoded = items.mapIndexed { index, item ->
            "$index:${item.first}:${item.second}"
        }.joinToString(",")
        assertEquals(
            "0:GROUP:releaseInFlight,1:CONFERENCE:requestEngine,2:CONFERENCE:releaseInFlight",
            encoded
        )
    }
}
