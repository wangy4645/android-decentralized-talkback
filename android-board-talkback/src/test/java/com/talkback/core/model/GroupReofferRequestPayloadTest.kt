package com.talkback.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupReofferRequestPayloadTest {
    @Test
    fun roundTrip_preservesChannelAndGeneration() {
        val original = GroupReofferRequestPayload(channelId = "CH-01", generation = 2L)
        assertEquals(original, GroupReofferRequestPayload.decode(original.encode()))
    }

    @Test
    fun decode_rejectsGarbage() {
        assertNull(GroupReofferRequestPayload.decode("not-json"))
    }
}
