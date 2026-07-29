package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.PeerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceIceConnectedSideEffectsTest {

    @Test
    fun g_r30_j_anchor_isolation_meshConference_bootstrapsReceivePath_notBackupStandby() {
        val meshConference = conferenceSession(GroupMediaTopology.MESH)

        assertTrue(
            ConferenceIceConnectedSideEffects.sessionsForReceivePathBootstrap(listOf(meshConference))
                .contains(meshConference)
        )
        assertFalse(
            ConferenceIceConnectedSideEffects.sessionsForBackupStandbyMaintenance(listOf(meshConference))
                .contains(meshConference)
        )
    }

    @Test
    fun g_r30_j_anchor_isolation_anchorConference_bootstrapsReceivePath_andBackupStandby() {
        val anchorConference = conferenceSession(GroupMediaTopology.ANCHOR)

        assertTrue(
            ConferenceIceConnectedSideEffects.sessionsForReceivePathBootstrap(listOf(anchorConference))
                .contains(anchorConference)
        )
        assertTrue(
            ConferenceIceConnectedSideEffects.sessionsForBackupStandbyMaintenance(listOf(anchorConference))
                .contains(anchorConference)
        )
    }

    @Test
    fun g_r30_j_anchor_isolation_anchorGroup_onlyBackupStandby_notReceivePathBootstrap() {
        val anchorGroup = groupSession(GroupMediaTopology.ANCHOR)

        assertFalse(
            ConferenceIceConnectedSideEffects.sessionsForReceivePathBootstrap(listOf(anchorGroup))
                .contains(anchorGroup)
        )
        assertTrue(
            ConferenceIceConnectedSideEffects.sessionsForBackupStandbyMaintenance(listOf(anchorGroup))
                .contains(anchorGroup)
        )
    }

    @Test
    fun g_r30_j_anchor_isolation_unacceptedConference_excludedFromBoth() {
        val unaccepted = conferenceSession(GroupMediaTopology.MESH).apply {
            accepted = false
        }

        assertEquals(
            emptyList<TalkbackSession>(),
            ConferenceIceConnectedSideEffects.sessionsForReceivePathBootstrap(listOf(unaccepted))
        )
        assertEquals(
            emptyList<TalkbackSession>(),
            ConferenceIceConnectedSideEffects.sessionsForBackupStandbyMaintenance(listOf(unaccepted))
        )
    }

    private fun conferenceSession(topology: GroupMediaTopology): TalkbackSession =
        TalkbackSession("conf-1", SessionType.CONFERENCE, local("M01"), "CH-01").apply {
            accepted = true
            mediaTopology = topology
            remotePeersByModule["M02"] = peerTarget()
        }

    private fun groupSession(topology: GroupMediaTopology): TalkbackSession =
        TalkbackSession("grp-1", SessionType.GROUP, local("M01"), "CH-01").apply {
            accepted = true
            mediaTopology = topology
            remotePeersByModule["M02"] = peerTarget()
        }

    private fun local(moduleId: String): EndpointAddress =
        EndpointAddress(ModuleId(moduleId), EndpointId("E01"))

    private fun peerTarget(): PeerTarget =
        PeerTarget(host = "127.0.0.1", port = 9_002)
}
