package com.talkback.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class FloorPayloadTest {
    @Test
    fun traceId_roundTrips_whenPresent() {
        val requester = EndpointAddress(ModuleId("M03"), EndpointId("E01"))
        val encoded = FloorPayload.forRequest(
            requester,
            floorVersion = 6L,
            floorEpoch = 2L,
            priority = EndpointPriority.NORMAL,
            traceId = 17L
        ).encode()
        val decoded = FloorPayload.decode(encoded)
        assertEquals(17L, decoded.traceId)
        assertEquals("M03-E01", decoded.requesterKey)
        assertEquals(6L, decoded.floorVersion)
        assertEquals(2L, decoded.floorEpoch)
    }

    @Test
    fun traceId_defaultsToZero_whenAbsentOnWire() {
        val legacy = """{"floorVersion":1,"floorEpoch":0,"priority":"NORMAL","requesterKey":"M01-E01"}"""
        val decoded = FloorPayload.decode(legacy)
        assertEquals(0L, decoded.traceId)
    }

    @Test
    fun traceId_omittedFromEncode_whenZero() {
        val encoded = FloorPayload(1L, 0L).encode()
        assertFalse(encoded.contains("traceId"))
        assertEquals(0L, FloorPayload.decode(encoded).traceId)
    }

    @Test
    fun traceId_preservedThroughGrantBroadcastPayload() {
        val grant = FloorPayload.forRequest(
            EndpointAddress(ModuleId("M03"), EndpointId("E01")),
            floorVersion = 7L,
            floorEpoch = 3L,
            priority = EndpointPriority.NORMAL,
            traceId = 42L
        )
        val roundTrip = FloorPayload.decode(grant.encode())
        assertEquals(42L, roundTrip.traceId)
        assertTrue(grant.encode().contains("\"traceId\":42"))
    }
}
