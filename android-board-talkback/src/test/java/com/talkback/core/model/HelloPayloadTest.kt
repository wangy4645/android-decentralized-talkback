package com.talkback.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class HelloPayloadTest {
    @Test
    fun encodeDecode_roundTrip_preservesPriority() {
        val payload = HelloPayload(
            moduleId = "M02",
            endpoints = listOf(
                RemoteEndpointInfo(
                    endpointId = "E01",
                    displayName = "Lead",
                    online = true,
                    priority = EndpointPriority.EMERGENCY
                )
            )
        )
        val decoded = HelloPayload.decode(payload.encode())
        assertNotNull(decoded)
        assertEquals(EndpointPriority.EMERGENCY, decoded!!.endpoints.single().priority)
    }

    @Test
    fun decode_missingPriority_defaultsNormal() {
        val raw = """{"moduleId":"M02","endpoints":[{"endpointId":"E01","displayName":"X","online":true}]}"""
        val decoded = HelloPayload.decode(raw)
        assertNotNull(decoded)
        assertEquals(EndpointPriority.NORMAL, decoded!!.endpoints.single().priority)
    }

    @Test
    fun encodeDecode_roundTrip_preservesTopologyDigest() {
        val payload = HelloPayload(
            moduleId = "M01",
            endpoints = emptyList(),
            channelId = "CH-01",
            rosterEpoch = 2L,
            meshGeneration = 0L,
            memberHash = 12345
        )
        val decoded = HelloPayload.decode(payload.encode())
        assertNotNull(decoded)
        assertEquals(2L, decoded!!.rosterEpoch)
        assertEquals(12345, decoded.memberHash)
    }

    @Test
    fun encodeDecode_roundTrip_preservesFloorSnapshot() {
        val snapshot = FloorSnapshotDigest(
            epoch = 5L,
            version = 6L,
            ownerKey = "M02-E01",
            ownerPriority = EndpointPriority.EMERGENCY
        )
        val payload = HelloPayload(
            moduleId = "M01",
            endpoints = emptyList(),
            channelId = "CH-01",
            floorSnapshot = snapshot
        )
        val decoded = HelloPayload.decode(payload.encode())
        assertNotNull(decoded)
        assertNotNull(decoded!!.floorSnapshot)
        assertEquals(5L, decoded.floorSnapshot!!.epoch)
        assertEquals(6L, decoded.floorSnapshot!!.version)
        assertEquals("M02-E01", decoded.floorSnapshot!!.ownerKey)
        assertEquals(EndpointPriority.EMERGENCY, decoded.floorSnapshot!!.ownerPriority)
    }

    @Test
    fun encodeDecode_vacantFloorSnapshot_ownerKeyNull() {
        val snapshot = FloorSnapshotDigest(epoch = 3L, version = 4L, ownerKey = null)
        val payload = HelloPayload(
            moduleId = "M01",
            endpoints = emptyList(),
            channelId = "CH-01",
            floorSnapshot = snapshot
        )
        val decoded = HelloPayload.decode(payload.encode())
        assertNotNull(decoded)
        assertNotNull(decoded!!.floorSnapshot)
        assertNull(decoded.floorSnapshot!!.ownerKey)
    }

    @Test
    fun encodeDecode_replicaHello_omitsFloorSnapshot() {
        val payload = HelloPayload(moduleId = "M03", endpoints = emptyList(), channelId = "CH-01")
        val raw = payload.encode()
        assertEquals(null, HelloPayload.decode(raw)!!.floorSnapshot)
    }
}
