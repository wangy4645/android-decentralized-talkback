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
class ConferenceTransmitBarrierIntegrationTest {

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
        val peers = TestTalkbackNode.allPeers(m01 to 50201, m02 to 50202, m03 to 50203)
        nodeM01 = TestTalkbackNode(context, m01, 50201, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50202, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50203, hub, peers)
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
    fun conference_edgeRecovery_keepsCaptureOnHealthyPeerPath() {
        val channelId = "CONF-TRANSMIT-BARRIER"
        val sessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("invite accepted") })
        connectConferenceHostIce(nodeM01, nodeM02, nodeM03)
        assertTrue(waitForCapturing(nodeM01, sessionId))

        nodeM01.runtime.simulateRemoteIceState("M03", "DISCONNECTED")
        val recoveringDeadline = System.currentTimeMillis() + 8_000L
        var recovering = false
        while (System.currentTimeMillis() < recoveringDeadline) {
            val snap = nodeM01.runtime.sessionSnapshots().first { it.sessionId == sessionId }
            if (snap.conferenceRuntimeState?.edgeRecovering == true) {
                recovering = true
                break
            }
            Thread.sleep(100L)
        }
        assertTrue("host should observe edgeRecovering for M03", recovering)
        assertTrue(
            "ADR-0026: M02 recovering must not conference-mute M01 while M01-M02 path is healthy",
            nodeM01.runtime.testCanPublishConferenceAudio(sessionId)
        )
        assertTrue(
            "capture should stay on while another peer path remains healthy",
            waitForCapturing(nodeM01, sessionId, timeoutMs = 2_000L)
        )
        assertFalse(
            nodeM01.hasLog {
                it.contains("CONFERENCE_BARRIER_SNAPSHOT") &&
                    it.contains("stop_capture") &&
                    it.contains("policy=EDGE_SCOPED")
            }
        )
    }

    private fun waitForCapturing(
        node: TestTalkbackNode,
        sessionId: String,
        timeoutMs: Long = 5_000L
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (node.runtime.testIsSessionCapturing(sessionId)) return true
            Thread.sleep(50L)
        }
        return false
    }
}

private fun TalkbackRuntime.requireConferenceCall(
    from: EndpointAddress,
    remoteEndpoints: List<EndpointAddress>,
    channelId: String
): String = requireNotNull(conferenceCall(from, remoteEndpoints, channelId)) { "conferenceCall blocked" }
