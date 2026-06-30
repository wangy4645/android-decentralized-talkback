package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class FloorLateGrantAuthorityIntegrationTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private lateinit var hub: InMemorySignalingHub
    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM02: TestTalkbackNode
    private lateinit var nodeM03: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        hub = InMemorySignalingHub()
        val peers = TestTalkbackNode.allPeers(m01 to 50601, m02 to 50602, m03 to 50603)
        nodeM01 = TestTalkbackNode(context, m01, 50601, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50602, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50603, hub, peers)
        nodeM01.start()
        nodeM02.start()
        nodeM03.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        nodeM03.stop()
    }

    @Test
    fun lateGrant_afterPttUp_allNodesHaveNoFloorOwner() {
        val channelId = "LATE-GRANT-AUTH"
        setupGroup(channelId)
        connectAnchorIce()
        val m02SessionId = nodeM02.runtime.activeSessionIds().single()
        nodeM02.pressPtt(m02SessionId)
        nodeM02.releasePtt(m02SessionId)
        assertTrue(waitForProtocolOwnerCleared(nodeM01, m01SessionId()))
        assertTrue(waitForProtocolOwnerCleared(nodeM02, m02SessionId))
        assertTrue(waitForProtocolOwnerCleared(nodeM03, nodeM03.runtime.activeSessionIds().single()))
        assertFalse(nodeM02.runtime.testIsSessionCapturing(m02SessionId))
    }

    private fun setupGroup(channelId: String) {
        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(
                    EndpointAddress(m02, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
        )
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        Thread.sleep(500L)
        assertNotNull(sessionId)
        listOf(nodeM01, nodeM02, nodeM03).forEach {
            it.runtime.testForceGroupAnchorTopology(channelId)
        }
    }

    private fun connectAnchorIce() {
        nodeM02.runtime.simulateRemoteIceState(ANCHOR_ID, "CONNECTED")
        nodeM03.runtime.simulateRemoteIceState(ANCHOR_ID, "CONNECTED")
        nodeM01.runtime.simulateRemoteIceState("M02", "CONNECTED")
        nodeM01.runtime.simulateRemoteIceState("M03", "CONNECTED")
        Thread.sleep(200L)
    }

    private fun m01SessionId(): String = nodeM01.runtime.activeSessionIds().single()

    private fun waitForProtocolOwnerCleared(node: TestTalkbackNode, sessionId: String): Boolean {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val owner = node.runtime.sessionPresenceSnapshot(sessionId)?.protocolFloorOwnerKey
            if (owner == null) return true
            Thread.sleep(50)
        }
        return false
    }

    companion object {
        private const val ANCHOR_ID = "M01"
    }
}
