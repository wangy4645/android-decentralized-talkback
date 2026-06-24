package com.talkback.core.model

import com.talkback.core.model.MeshSessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupSessionPayloadTest {
    @Test
    fun encodeDecodeRoundTrip() {
        val original = GroupSessionPayload(
            sdp = "v=0",
            channelId = "CH-01",
            members = listOf("M01-E01", "M02-E01", "M03-E01"),
            initiatorModuleId = "M01",
            floorAuthorityModuleId = "M01"
        )
        val decoded = GroupSessionPayload.decode(original.encode())
        assertNotNull(decoded)
        assertEquals(original.sdp, decoded!!.sdp)
        assertEquals(original.channelId, decoded.channelId)
        assertEquals(original.members, decoded.members)
        assertEquals(original.initiatorModuleId, decoded.initiatorModuleId)
        assertEquals(original.floorAuthorityModuleId, decoded.floorAuthorityModuleId)
    }

    @Test
    fun encodeDecodeConferenceMode() {
        val original = GroupSessionPayload(
            sdp = "v=0",
            channelId = "CONF-01",
            members = listOf("M01-E01", "M02-E01"),
            initiatorModuleId = "M01",
            floorAuthorityModuleId = "M01",
            sessionMode = MeshSessionMode.CONFERENCE
        )
        val decoded = GroupSessionPayload.decode(original.encode())
        assertNotNull(decoded)
        assertEquals(MeshSessionMode.CONFERENCE, decoded!!.sessionMode)
    }

    @Test
    fun decodeMissingModeDefaultsToGroup() {
        val raw = """{"sdp":"v=0","channelId":"CH-01","members":["M01-E01"],"initiatorModuleId":"M01","floorAuthorityModuleId":"M01"}"""
        val decoded = GroupSessionPayload.decode(raw)
        assertNotNull(decoded)
        assertEquals(MeshSessionMode.GROUP, decoded!!.sessionMode)
    }

    @Test
    fun parseMembersFromKeys() {
        val members = GroupSessionPayload.parseMembers(listOf("M01-E01", "M02-E02"))
        assertEquals(2, members.size)
        assertEquals("M01-E01", members[0].key)
        assertEquals("M02-E02", members[1].key)
    }
}
