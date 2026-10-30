package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.webrtc.MediaBearerScope
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferencePeerReceivePlaybackGateTest {

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
        val peers = TestTalkbackNode.allPeers(m01 to 50301, m02 to 50302, m03 to 50303)
        nodeM01 = TestTalkbackNode(context, m01, 50301, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50302, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50303, hub, peers)
        nodeM01.start()
        nodeM02.start()
        nodeM03.start()
        nodeM01.runtime.setAutoAcceptConferenceInvites(true)
        nodeM02.runtime.setAutoAcceptConferenceInvites(true)
        nodeM03.runtime.setAutoAcceptConferenceInvites(true)
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        nodeM03.stop()
    }

    @Test
    fun iceChecking_disablesPeerPlayback() {
        val sessionId = startConference("CONF-RX-GATE-CHECKING")
        connectConferenceHostIce(nodeM02, nodeM01, nodeM03, hostModuleId = "M02")
        assertTrue(waitForPeerPlayback(nodeM02, "M01", enabled = true))

        nodeM02.runtime.simulateMeshIceState(MediaBearerScope.CONFERENCE, "M01", "CHECKING")
        nodeM02.runtime.testRefreshConferenceReceivePlayback(sessionId, reason = "test_checking")
        Thread.sleep(500L)
        assertFalse(nodeM02.runtime.testIsPeerRemotePlaybackEnabled("M01"))
    }

    @Test
    fun iceConnected_enablesPeerPlayback() {
        val sessionId = startConference("CONF-RX-GATE-CONNECTED")
        connectConferenceHostIce(nodeM02, nodeM01, nodeM03, hostModuleId = "M02")
        assertTrue(waitForSessionPlayback(nodeM02, sessionId, enabled = true))
        assertTrue(waitForPeerPlayback(nodeM02, "M01", enabled = true))
        assertTrue(waitForPeerPlayback(nodeM02, "M03", enabled = true))
    }

    @Test
    fun iceDisconnected_disablesPeerPlayback() {
        val sessionId = startConference("CONF-RX-GATE-DISCONNECTED")
        connectConferenceHostIce(nodeM02, nodeM01, nodeM03, hostModuleId = "M02")
        assertTrue(waitForSessionPlayback(nodeM02, sessionId, enabled = true))
        assertTrue(waitForPeerPlayback(nodeM02, "M01", enabled = true))

        nodeM02.runtime.simulateMeshIceState(MediaBearerScope.CONFERENCE, "M01", "DISCONNECTED")
        nodeM02.runtime.testRefreshConferenceReceivePlayback(sessionId, reason = "test_disconnected")
        Thread.sleep(500L)
        assertFalse(nodeM02.runtime.testIsPeerRemotePlaybackEnabled("M01"))
    }

    private fun startConference(channelId: String): String {
        val sessionId = requireNotNull(
            nodeM02.runtime.conferenceCall(
                nodeM02.localEndpoint,
                listOf(
                    EndpointAddress(m01, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
        ) { "conferenceCall blocked" }
        assertTrue(nodeM01.waitForLog { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("invite accepted") })
        return sessionId
    }

    private fun waitForSessionPlayback(
        node: TestTalkbackNode,
        sessionId: String,
        enabled: Boolean,
        timeoutMs: Long = 5_000L
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (node.runtime.testIsSessionPlaybackEnabled(sessionId) == enabled) return true
            Thread.sleep(50L)
        }
        return node.runtime.testIsSessionPlaybackEnabled(sessionId) == enabled
    }

    private fun waitForPeerPlayback(
        node: TestTalkbackNode,
        peerModuleId: String,
        enabled: Boolean,
        timeoutMs: Long = 5_000L
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (node.runtime.testIsPeerRemotePlaybackEnabled(peerModuleId) == enabled) return true
            Thread.sleep(50L)
        }
        return node.runtime.testIsPeerRemotePlaybackEnabled(peerModuleId) == enabled
    }
}
