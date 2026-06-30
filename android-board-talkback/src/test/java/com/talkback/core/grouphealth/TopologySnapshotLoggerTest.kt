package com.talkback.core.grouphealth

import com.talkback.core.session.ChannelReadiness
import com.talkback.core.session.GroupMediaTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopologySnapshotLoggerTest {

    @Test
    fun format_includesTagAndAllFields() {
        val health = GroupRuntimeHealth(
            schemaVersion = 1,
            sessionId = "s1",
            localModuleId = "M02",
            topologyKind = GroupMediaTopology.MESH,
            members = listOf("M01", "M02", "M03"),
            rosterEpoch = 2L,
            memberHash = 42,
            sessionAccepted = true,
            membershipDigestAligned = true,
            suspectPeers = emptySet(),
            membershipReconciled = true,
            meshDesiredLinks = setOf(MeshLink("M02", "M03")),
            meshSignaledPeers = setOf("M01"),
            meshIceConnectedPeers = setOf("M01", "M03"),
            meshMissingSignal = setOf("M03"),
            meshMissingIce = emptySet(),
            transmitRequiredPeers = setOf("M01", "M03"),
            transmitReadyPeers = setOf("M01"),
            transmitMissingPeers = setOf("M03"),
            groupTopologyReadiness = GroupTopologyReadiness.BUILDING,
            mappedChannelReadiness = ChannelReadiness.DIRECTORY_SYNC,
            convergenceAgeMs = 1500L,
            floorOwnerKey = "M01-E01",
            floorEpoch = 1L,
            floorVersion = 2L,
            channelGated = false
        )

        val line = TopologySnapshotLogger.format(TopologySnapshotReason.READINESS_CHANGED, health)

        assertTrue(line.startsWith("TOPOLOGY_SNAPSHOT "))
        assertEquals(
            "TOPOLOGY_SNAPSHOT reason=READINESS_CHANGED schemaVersion=1 sessionId=s1 localModuleId=M02 " +
                "topologyKind=MESH members=M01,M02,M03 rosterEpoch=2 memberHash=42 sessionAccepted=true " +
                "membershipDigestAligned=true suspectPeers= membershipReconciled=true " +
                "meshDesiredLinks=M02->M03 meshSignaledPeers=M01 meshIceConnectedPeers=M01,M03 " +
                "meshMissingSignal=M03 meshMissingIce= transmitRequiredPeers=M01,M03 " +
                "transmitReadyPeers=M01 transmitMissingPeers=M03 groupTopologyReadiness=BUILDING " +
                "mappedChannelReadiness=DIRECTORY_SYNC convergenceAgeMs=1500 floorOwner=M01-E01 " +
                "floorEpoch=1 floorVersion=2 channelGated=false",
            line
        )
    }
}
