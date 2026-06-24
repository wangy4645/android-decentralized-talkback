package com.talkback.core.model

import com.talkback.core.session.AnchorRanking
import com.talkback.core.session.GroupMediaTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupSessionPayloadAnchorEpochTest {
    @Test
    fun encodeDecodeAnchorEpochAndBackup() {
        val original = GroupSessionPayload(
            sdp = "stub-offer",
            channelId = "CH-01",
            members = listOf("M01-E01", "M02-E01", "M03-E01"),
            initiatorModuleId = "M01",
            floorAuthorityModuleId = "M01",
            mediaTopology = GroupMediaTopology.ANCHOR.encode(),
            anchorModuleId = "M01",
            backupAnchorModuleId = "M02",
            anchorEpoch = AnchorRanking.INITIAL_ANCHOR_EPOCH
        )
        val decoded = GroupSessionPayload.decode(original.encode())
        assertNotNull(decoded)
        assertEquals("M02", decoded!!.backupAnchorModuleId)
        assertEquals(AnchorRanking.INITIAL_ANCHOR_EPOCH, decoded.anchorEpoch)
    }
}
