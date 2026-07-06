package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.ptt.PttState
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class FloorLateGrantDiscardIntegrationTest {
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
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        nodeM03.stop()
    }

    @Test
    fun lateGrant_afterPttUp_discardsOwnershipOnRequester() {
        val channelId = "LATE-GRANT"
        setupGroup(channelId)
        connectAnchorIce(channelId)
        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        val requestVersion = nodeM03.runtime.testArmFloorRequest(m03SessionId)
        assertTrue(requestVersion > 0L)
        nodeM03.runtime.testCancelFloorRequest(m03SessionId)
        assertEquals(PttState.IDLE, nodeM03.runtime.sessionSnapshots().first { it.sessionId == m03SessionId }.localPttState)
        nodeM03.runtime.testInjectFloorGranted(
            sessionId = m03SessionId,
            authority = nodeM01.localEndpoint,
            grantee = nodeM03.localEndpoint,
            floorVersion = requestVersion
        )
        Thread.sleep(200L)
        val snap = nodeM03.runtime.sessionSnapshots().first { it.sessionId == m03SessionId }
        assertNull(snap.protocolFloorOwnerKey)
        assertFalse(nodeM03.runtime.testIsSessionCapturing(m03SessionId))
        assertTrue(nodeM03.hasLog { it.contains("COMPLETION_DISCARDED") && it.contains("TOKEN_INVALID") })
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

    private fun connectAnchorIce(channelId: String) {
        connectGroupAnchorIce(nodeM01, nodeM02, nodeM03, channelId, ANCHOR_ID)
    }

    companion object {
        private const val ANCHOR_ID = "M01"
    }
}
