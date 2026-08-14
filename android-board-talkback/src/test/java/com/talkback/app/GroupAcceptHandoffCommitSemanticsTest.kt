package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.InviteState
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Phase-2 P2-B: session.accepted requires successful GROUP_ACCEPT handoff delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupAcceptHandoffCommitSemanticsTest {

    private val m01 = ModuleId("M01")
    private val m03 = ModuleId("M03")
    private val hub = InMemorySignalingHub()

    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM03: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50691, m03 to 50693)
        nodeM01 = TestTalkbackNode(
            context = context,
            moduleId = m01,
            port = 50691,
            hub = hub,
            allPeers = peers,
            autoAcceptIncoming = false
        )
        nodeM03 = TestTalkbackNode(context, m03, 50693, hub, peers, autoAcceptIncoming = true)
        nodeM01.start()
        nodeM03.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM03.stop()
    }

    @Test
    fun blockedGroupAcceptHandoff_doesNotCommitSessionAccepted() {
        val channelId = "ACCEPT-COMMIT-1"
        nodeM03.runtime.testBlockPeerControlSignaling("M01", blocked = true)
        val m03LogMark = synchronized(nodeM03.logs) { nodeM03.logs.size }

        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(EndpointAddress(m03, EndpointId("E01"))),
                channelId
            )
        )

        assertTrue(
            nodeM03.waitForLogSince(m03LogMark, timeoutMs = 8_000L) {
                it.contains("GROUP_ACCEPT_HANDOFF") &&
                    it.contains("result=FAIL") &&
                    it.contains("commit=SKIPPED")
            }
        )
        assertFalse(nodeM03.runtime.testIsSessionAccepted(sessionId))
        assertEquals(
            InviteState.RINGING.name,
            nodeM03.runtime.testParticipantInviteState(sessionId, "M01")
        )
    }

    @Test
    fun successfulGroupAcceptHandoff_commitsSessionAccepted() {
        val channelId = "ACCEPT-COMMIT-2"
        val m03LogMark = synchronized(nodeM03.logs) { nodeM03.logs.size }

        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(EndpointAddress(m03, EndpointId("E01"))),
                channelId
            )
        )

        assertTrue(
            nodeM03.waitForLogSince(m03LogMark, timeoutMs = 8_000L) {
                it.contains("GROUP_ACCEPT_HANDOFF") &&
                    it.contains("result=SUCCESS")
            }
        )
        assertTrue(nodeM03.runtime.testIsSessionAccepted(sessionId))
        assertEquals(
            InviteState.ACCEPTED.name,
            nodeM03.runtime.testParticipantInviteState(sessionId, "M01")
        )
    }
}
