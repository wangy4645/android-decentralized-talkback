package com.talkback.core.model

import com.talkback.core.session.GroupMediaTopology
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupSessionPayloadAnchorTest {
    @Test
    fun decodeGroupInvitePayload_withMeshTopology() {
        val encoded = GroupSessionPayload(
            sdp = "stub-offer-test-uuid",
            channelId = "CH-01",
            members = listOf("M01-E01", "M02-E01", "M03-E01"),
            initiatorModuleId = "M01",
            floorAuthorityModuleId = "M01",
            mediaTopology = GroupMediaTopology.MESH.encode(),
            anchorModuleId = "M01"
        ).encode()
        assertNotNull(GroupSessionPayload.decode(encoded))
    }
}
