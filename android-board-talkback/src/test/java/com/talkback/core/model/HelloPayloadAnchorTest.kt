package com.talkback.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class HelloPayloadAnchorTest {
    @Test
    fun encodeDecodeHealthAndAnchorFields() {
        val original = HelloPayload(
            moduleId = "M01",
            endpoints = emptyList(),
            charging = true,
            batteryPercent = 88,
            onlineSinceMs = 12345L,
            anchorEpoch = 101L,
            primaryModuleId = "M01",
            backupModuleId = "M02",
            channelId = "CH-01"
        )
        val decoded = HelloPayload.decode(original.encode())
        assertNotNull(decoded)
        assertEquals(true, decoded!!.charging)
        assertEquals(88, decoded.batteryPercent)
        assertEquals(101L, decoded.anchorEpoch)
        assertEquals("M02", decoded.backupModuleId)
    }
}
