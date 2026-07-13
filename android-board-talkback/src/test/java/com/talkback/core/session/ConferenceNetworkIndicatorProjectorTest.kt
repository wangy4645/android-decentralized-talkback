package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ConferenceNetworkIndicatorProjectorTest {

    @Test
    fun empty_mapsToUnknown() {
        assertEquals(
            ConferenceNetworkIndicator.UNKNOWN,
            ConferenceNetworkIndicatorProjector.project(emptyList())
        )
    }

    @Test
    fun anyFailed_mapsToDegraded() {
        assertEquals(
            ConferenceNetworkIndicator.DEGRADED,
            ConferenceNetworkIndicatorProjector.project(listOf("CONNECTED", "FAILED"))
        )
    }

    @Test
    fun anyDisconnected_mapsToDegraded() {
        assertEquals(
            ConferenceNetworkIndicator.DEGRADED,
            ConferenceNetworkIndicatorProjector.project(listOf("COMPLETED", "DISCONNECTED"))
        )
    }

    @Test
    fun allConnectedOrCompleted_mapsToExcellent() {
        assertEquals(
            ConferenceNetworkIndicator.EXCELLENT,
            ConferenceNetworkIndicatorProjector.project(listOf("CONNECTED", "COMPLETED"))
        )
    }

    @Test
    fun mixedNegotiatingWithoutLoss_mapsToGood() {
        assertEquals(
            ConferenceNetworkIndicator.GOOD,
            ConferenceNetworkIndicatorProjector.project(listOf("CONNECTED", "CHECKING"))
        )
    }

    @Test
    fun closedOnly_mapsToGood() {
        assertEquals(
            ConferenceNetworkIndicator.GOOD,
            ConferenceNetworkIndicatorProjector.project(listOf("CLOSED"))
        )
    }

    @Test
    fun toQualityLabel_matchesLegacyStrings() {
        assertEquals("Excellent", ConferenceNetworkIndicator.EXCELLENT.toQualityLabel())
        assertEquals("Good", ConferenceNetworkIndicator.GOOD.toQualityLabel())
        assertEquals("Poor", ConferenceNetworkIndicator.DEGRADED.toQualityLabel())
        assertEquals("N/A", ConferenceNetworkIndicator.UNKNOWN.toQualityLabel())
    }
}
