package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.InMemorySignalingHub
import com.talkback.core.session.CppEvidence
import com.talkback.core.session.CppMediaRelation
import com.talkback.core.webrtc.MediaBearerScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * OPS-08: participant peer mesh GROUP_ACCEPT must not enter OPS-07 host CR fail-closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Ops08ConferencePeerMeshAcceptIntegrationTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val hub = InMemorySignalingHub()
    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM02: TestTalkbackNode
    private lateinit var nodeM03: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(
            m01 to 50031,
            m02 to 50032,
            m03 to 50033,
        )
        nodeM01 = TestTalkbackNode(context, m01, 50031, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50032, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50033, hub, peers)
        nodeM01.start()
        nodeM02.start()
        nodeM03.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        nodeM03.stop()
        TestTalkbackNode.resetSharedOperationalNetworkRegistryForTest()
    }

    @Test
    fun hostCrMatchPath_unchanged_ops07Regression() {
        val channelId = "OPS08-HOST-CR"
        nodeM03.runtime.setAutoAcceptConferenceInvites(false)

        nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01")),
            ),
            channelId,
        )

        assertTrue(
            nodeM01.waitForLog(timeoutMs = 10_000L) {
                it.contains("APPLY_REMOTE_ANSWER") &&
                    it.contains("remote=M02") &&
                    it.contains("lineageCorrelation=MATCH")
            }
        )

        assertTrue(nodeM03.runtime.acceptPendingConferenceInvite(channelId))

        assertTrue(
            nodeM01.waitForLog(timeoutMs = 15_000L) {
                it.contains("APPLY_REMOTE_ANSWER") &&
                    it.contains("remote=M03") &&
                    it.contains("lineageCorrelation=MATCH")
            }
        )
        assertFalse(
            nodeM01.hasLog {
                it.contains("CONFERENCE_ACCEPT_FAIL_CLOSED") && it.contains("peer=M03")
            }
        )
    }

    @Test
    fun afterHostAccepts_peerMeshAccept_noHostFailClosed() {
        val channelId = "OPS08-PEER-MESH"
        nodeM03.runtime.setAutoAcceptConferenceInvites(false)

        nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01")),
            ),
            channelId,
        )

        assertTrue(
            nodeM02.waitForLog(timeoutMs = 10_000L) {
                it.contains("Conference invite accepted") || it.contains("invite accepted")
            }
        )
        connectConferenceHostIce(nodeM01, nodeM02, nodeM03)
        assertTrue(nodeM03.runtime.acceptPendingConferenceInvite(channelId))

        assertTrue(
            nodeM01.waitForLog(timeoutMs = 15_000L) {
                it.contains("APPLY_REMOTE_ANSWER") && it.contains("remote=M03")
            }
        )
        assertTrue(
            nodeM02.waitForLog(timeoutMs = 15_000L) {
                it.contains("Group mesh join offered -> M03")
            }
        )

        assertTrue(
            nodeM02.waitForLog(timeoutMs = 25_000L) {
                it.contains("GROUP_ACCEPT_EXEC stage=EXIT") && it.contains("peer=M03")
            }
        )

        assertFalse(
            nodeM02.hasLog {
                it.contains("CONFERENCE_ACCEPT_FAIL_CLOSED") && it.contains("peer=M03")
            }
        )

        val m02PeerAcceptExits = nodeM02.logs.filter {
            it.contains("GROUP_ACCEPT_EXEC stage=EXIT") && it.contains("peer=M03")
        }
        assertTrue(m02PeerAcceptExits.isNotEmpty())
        assertTrue(
            m02PeerAcceptExits.any {
                it.contains("reason=PEER_MESH_APPLY") || it.contains("reason=MESH_ALREADY_CONNECTED")
            }
        )
        assertTrue(
            m02PeerAcceptExits.none { it.contains("ACCEPT_FAIL_CLOSED") }
        )
    }

    @Test
    fun m02M03PeerMeshConvergence_joiningZero() {
        val channelId = "OPS08-PEER-CONV"
        nodeM03.runtime.setAutoAcceptConferenceInvites(false)

        nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01")),
            ),
            channelId,
        )

        assertTrue(
            nodeM02.waitForLog(timeoutMs = 10_000L) {
                it.contains("Conference invite accepted") || it.contains("invite accepted")
            }
        )
        connectConferenceHostIce(nodeM01, nodeM02, nodeM03)
        assertTrue(nodeM03.runtime.acceptPendingConferenceInvite(channelId))

        assertTrue(
            nodeM02.waitForLog(timeoutMs = 15_000L) {
                it.contains("CONFERENCE_PEER_MESH_ACCEPT peer=M03") ||
                    it.contains("reason=PEER_MESH_APPLY") ||
                    it.contains("reason=MESH_ALREADY_CONNECTED")
            }
        )

        val iceMark = synchronized(nodeM02.logs) { nodeM02.logs.size }
        nodeM02.runtime.simulateMeshIceState(MediaBearerScope.CONFERENCE, "M03", "CONNECTED")
        nodeM03.runtime.simulateMeshIceState(MediaBearerScope.CONFERENCE, "M02", "CONNECTED")

        assertTrue(
            nodeM02.waitForLogSince(iceMark, timeoutMs = 10_000L) {
                it.contains("ICE M03 scope=CONFERENCE state=CONNECTED")
            }
        )

        val deadline = System.currentTimeMillis() + 10_000L
        var m03: com.talkback.core.session.ParticipantPresenceRecord? = null
        var meshConnected = false
        while (System.currentTimeMillis() < deadline) {
            val snap = nodeM02.runtime.sessionSnapshots().firstOrNull { it.channelId == channelId }
            m03 = snap?.conferencePresenceProjection?.participants?.singleOrNull { it.moduleId == "M03" }
            if (snap != null && snap.meshConnectedPeerCount >= 1 && m03?.mediaConnected == true) {
                meshConnected = true
                break
            }
            Thread.sleep(50)
        }
        assertTrue(meshConnected)
        assertNotNull(m03)
        assertEquals(CppMediaRelation.DIRECT, m03!!.mediaRelation)
        assertEquals(CppEvidence.FRESH, m03.evidence)
        assertNotEquals(CppMediaRelation.NONE, m03.mediaRelation)
        assertNotEquals(CppEvidence.UNKNOWN, m03.evidence)
        assertFalse(
            nodeM02.hasLog {
                it.contains("CONFERENCE_ACCEPT_FAIL_CLOSED") && it.contains("peer=M03")
            }
        )
    }
}

private fun TalkbackRuntime.requireConferenceCall(
    from: EndpointAddress,
    remoteEndpoints: List<EndpointAddress>,
    channelId: String,
): String = requireNotNull(conferenceCall(from, remoteEndpoints, channelId)) {
    "conferenceCall blocked"
}
