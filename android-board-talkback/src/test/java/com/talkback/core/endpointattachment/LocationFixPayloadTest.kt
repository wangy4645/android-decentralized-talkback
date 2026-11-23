package com.talkback.core.endpointattachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocationFixPayloadTest {
    @Test
    fun roundTrip_withLabelAndCrs() {
        val original = LocationFixPayload(
            latitude = 31.2304,
            longitude = 121.4737,
            accuracyMeters = 12f,
            altitudeMeters = 10.0,
            timestampMs = 1_700_000_000_000L,
            provider = "gps",
            label = "上海市黄浦区",
            coordinateSystem = LocationCoordinateSystem.WGS84,
        )
        val decoded = LocationFixPayload.decode(original.encode())
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun decode_legacyWithoutLabel() {
        val json = """{"v":1,"lat":31.23,"lon":121.47,"tsMs":1,"provider":"gps"}"""
        val decoded = LocationFixPayload.decode(json.toByteArray())
        assertNotNull(decoded)
        assertNull(decoded!!.label)
        assertEquals(LocationCoordinateSystem.WGS84, decoded.coordinateSystem)
    }
}