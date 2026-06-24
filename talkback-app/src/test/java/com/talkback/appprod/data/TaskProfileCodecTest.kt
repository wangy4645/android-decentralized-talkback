package com.talkback.appprod.data

import com.talkback.core.model.EndpointPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TaskProfileCodecTest {
    @Test
    fun encodeDecode_roundTrip_preservesFields() {
        val original = TaskProfile.createNew(
            name = "Alpha Team",
            sharedSecret = "secret-1",
            channelId = "CH-A",
            channelDisplayName = "Alpha",
            staticPeersJson = "[]",
            rfKeyLabel = "Key-A",
            localPriority = EndpointPriority.DISPATCH
        )
        val decoded = TaskProfile.decodeList(TaskProfile.encodeList(listOf(original))).single()
        assertEquals(original.id, decoded.id)
        assertEquals(original.name, decoded.name)
        assertEquals(original.sharedSecret, decoded.sharedSecret)
        assertEquals(original.channelId, decoded.channelId)
        assertEquals(original.rfKeyLabel, decoded.rfKeyLabel)
        assertEquals(EndpointPriority.DISPATCH, decoded.localPriority)
    }

    @Test
    fun decodeList_missingPriority_defaultsNormal() {
        val json = """[{"id":"p1","name":"T","sharedSecret":"s","channelId":"CH-01"}]"""
        val decoded = TaskProfile.decodeList(json).single()
        assertEquals(EndpointPriority.NORMAL, decoded.localPriority)
    }

    @Test
    fun decodeList_blank_returnsEmpty() {
        assertTrue(TaskProfile.decodeList("").isEmpty())
        assertTrue(TaskProfile.decodeList("   ").isEmpty())
    }
}
