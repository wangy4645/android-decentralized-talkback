package com.talkback.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MembershipSnapshotTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val original = MembershipSnapshot(
            rosterEpoch = 2L,
            anchorEpoch = 101L,
            members = listOf("M01-E01", "M02-E01", "M03-E01")
        )
        val decoded = MembershipSnapshot.decode(original.encode())
        assertNotNull(decoded)
        assertEquals(2L, decoded!!.rosterEpoch)
        assertEquals(101L, decoded.anchorEpoch)
        assertEquals(original.members, decoded.members)
    }

    @Test
    fun groupSessionPayload_carriesMembershipSnapshot() {
        val snapshot = MembershipSnapshot(
            rosterEpoch = 2L,
            anchorEpoch = 0L,
            members = listOf("M01-E01", "M03-E01")
        )
        val payload = GroupSessionPayload(
            sdp = "",
            channelId = "CH-01",
            members = snapshot.members,
            initiatorModuleId = "M01",
            floorAuthorityModuleId = "M01",
            rosterEpoch = 2L,
            rejoin = true,
            membershipSnapshot = snapshot
        )
        val decoded = GroupSessionPayload.decode(payload.encode())
        assertNotNull(decoded!!.membershipSnapshot)
        assertEquals(2L, decoded.membershipSnapshot!!.rosterEpoch)
        assertEquals(snapshot.members, decoded.membershipSnapshot!!.members)
    }
}
