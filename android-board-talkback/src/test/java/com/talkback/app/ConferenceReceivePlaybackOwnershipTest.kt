package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
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
class ConferenceReceivePlaybackOwnershipTest {

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
    fun conferenceMute_keepsReceivePlaybackEnabled_captureOff() {
        val channelId = "CONF-RX-MUTE"
        val sessionId = startConference(channelId)
        connectConferenceHostIce(nodeM02, nodeM01, nodeM03, hostModuleId = "M02")
        assertTrue(waitForPlayback(nodeM02, sessionId, enabled = true))

        nodeM02.runtime.setCallMuted(sessionId, muted = true, reason = "toggleMeetingMute")
        Thread.sleep(200L)

        assertTrue(nodeM02.runtime.testIsSessionPlaybackEnabled(sessionId))
        assertFalse(nodeM02.runtime.testIsSessionCapturing(sessionId))
    }

    @Test
    fun recoveryRefresh_reopensStuckConferenceReceivePlayback() {
        val channelId = "CONF-RX-RECOVERY"
        val sessionId = startConference(channelId)
        connectConferenceHostIce(nodeM02, nodeM01, nodeM03, hostModuleId = "M02")
        assertTrue(waitForPlayback(nodeM01, sessionId, enabled = true))

        nodeM01.runtime.testSetSessionPlaybackEnabled(sessionId, enabled = false, reason = "test_stuck")
        assertFalse(nodeM01.runtime.testIsSessionPlaybackEnabled(sessionId))

        nodeM01.runtime.testRefreshConferenceReceivePlayback(sessionId, reason = "recovery_state_changed")
        assertTrue(nodeM01.runtime.testIsSessionPlaybackEnabled(sessionId))
    }

    @Test
    fun iceReconnect_afterMute_restoresConferenceReceivePlayback() {
        val channelId = "CONF-RX-ICE"
        val sessionId = startConference(channelId)
        connectConferenceHostIce(nodeM02, nodeM01, nodeM03, hostModuleId = "M02")
        assertTrue(waitForPlayback(nodeM01, sessionId, enabled = true))

        nodeM01.runtime.setCallMuted(sessionId, muted = true, reason = "toggleMeetingMute")
        nodeM01.runtime.simulateRemoteIceState("M02", "DISCONNECTED")
        nodeM01.runtime.simulateRemoteIceState("M03", "DISCONNECTED")
        nodeM01.runtime.testSetSessionPlaybackEnabled(sessionId, enabled = false, reason = "test_stuck")
        assertFalse(nodeM01.runtime.testIsSessionPlaybackEnabled(sessionId))

        nodeM01.runtime.simulateRemoteIceState("M02", "CONNECTED")
        nodeM01.runtime.simulateRemoteIceState("M03", "CONNECTED")
        nodeM02.runtime.simulateRemoteIceState("M01", "CONNECTED")
        nodeM03.runtime.simulateRemoteIceState("M01", "CONNECTED")

        assertTrue(waitForPlayback(nodeM01, sessionId, enabled = true, timeoutMs = 5_000L))
        assertFalse(
            nodeM01.hasLog {
                it.contains("PLAYBACK") &&
                    it.contains("new=false") &&
                    it.contains("reason=acceptGroupJoin")
            }
        )
    }

    @Test
    fun transportRefresh_afterMute_doesNotDisableConferencePlayback() {
        val channelId = "CONF-RX-TRANSPORT"
        val sessionId = startConference(channelId)
        connectConferenceHostIce(nodeM02, nodeM01, nodeM03, hostModuleId = "M02")
        assertTrue(waitForPlayback(nodeM02, sessionId, enabled = true))

        nodeM02.runtime.setCallMuted(sessionId, muted = true, reason = "toggleMeetingMute")
        nodeM02.runtime.testRefreshConferenceReceivePlayback(sessionId, reason = "acceptGroupJoin")

        assertTrue(nodeM02.runtime.testIsSessionPlaybackEnabled(sessionId))
        assertFalse(
            nodeM02.hasLog {
                it.contains("PLAYBACK") &&
                    it.contains("new=false") &&
                    it.contains("reason=acceptGroupJoin")
            }
        )
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

    private fun waitForPlayback(
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
}
