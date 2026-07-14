package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ADR-0026: remote obligation open must not stop capture when another peer path is healthy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferenceTransmitBarrierResumeIntegrationTest {

    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val hub = InMemorySignalingHub()
    private lateinit var host: TestTalkbackNode
    private lateinit var participant: TestTalkbackNode
    private lateinit var peer: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50301, m02 to 50302, m03 to 50303)
        host = TestTalkbackNode(
            context,
            m01,
            50301,
            hub,
            peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L,
            edgeRecoveryObservationWindowMs = 2_000L
        )
        participant = TestTalkbackNode(
            context,
            m02,
            50302,
            hub,
            peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L,
            edgeRecoveryObservationWindowMs = 2_000L
        )
        peer = TestTalkbackNode(
            context,
            m03,
            50303,
            hub,
            peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L,
            edgeRecoveryObservationWindowMs = 2_000L
        )
        host.start()
        participant.start()
        peer.start()
        host.runtime.setAutoAcceptConferenceInvites(true)
        participant.runtime.setAutoAcceptConferenceInvites(true)
        peer.runtime.setAutoAcceptConferenceInvites(true)
    }

    @After
    fun tearDown() {
        host.stop()
        participant.stop()
        peer.stop()
    }

    @Test
    fun conference_remoteObligationOpen_keepsCaptureOnHealthyPath() {
        val channelId = "CONF-BARRIER-EDGE-SCOPED"
        val sessionId = host.runtime.requireConferenceCall(
            host.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertTrue(participant.waitForLog { it.contains("invite accepted") })
        assertTrue(peer.waitForLog { it.contains("invite accepted") })
        connectConferenceHostIce(host, participant, peer)
        participant.runtime.simulateRemoteIceState("M03", "CONNECTED")
        peer.runtime.simulateRemoteIceState("M02", "CONNECTED")
        peer.runtime.simulateRemoteIceState("M01", "CONNECTED")

        assertTrue("precondition: captureON before remote loss", waitForCapturing(host, sessionId))
        assertTrue(host.runtime.testCanPublishConferenceAudio(sessionId))

        val blockMark = synchronized(host.logs) { host.logs.size }
        host.runtime.simulateRemoteIceState("M03", "DISCONNECTED")
        assertTrue(
            host.waitForLogSince(blockMark, timeoutMs = 8_000L) {
                it.contains("FAILED_MEDIA_RECOVERY") && it.contains("remote=M03")
            }
        )
        assertEventually(
            timeoutMs = 5_000L,
            message = "edge-scoped gate should keep publish allowed while M02 path is healthy"
        ) {
            host.runtime.testCanPublishConferenceAudio(sessionId)
        }
        assertEventually(
            timeoutMs = 5_000L,
            message = "capture should remain on while another peer path is healthy"
        ) {
            host.runtime.testIsSessionCapturing(sessionId)
        }
    }

    private fun waitForCapturing(node: TestTalkbackNode, sessionId: String, timeoutMs: Long = 5_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (node.runtime.testIsSessionCapturing(sessionId)) return true
            Thread.sleep(50L)
        }
        return false
    }

    private fun assertEventually(
        timeoutMs: Long,
        message: String,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50L)
        }
        assertTrue(message, condition())
    }
}

private fun TestTalkbackNode.hasLogSince(mark: Int, predicate: (String) -> Boolean): Boolean =
    synchronized(logs) { logs.drop(mark).any(predicate) }

private fun TalkbackRuntime.requireConferenceCall(
    from: EndpointAddress,
    remoteEndpoints: List<EndpointAddress>,
    channelId: String
): String = requireNotNull(conferenceCall(from, remoteEndpoints, channelId)) { "conferenceCall blocked" }
