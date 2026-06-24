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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Invariant-F1: captureON only after floor grant (GROUP PTT).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class FloorInvariantIntegrationTest {
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
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        nodeM03.stop()
    }

    /** T1: follower PTT without grant must not capture. */
    @Test
    fun followerPttWithoutGrant_doesNotCapture() {
        val channelId = "F1-NO-GRANT"
        setupGroup(channelId)
        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.pressPtt(m03SessionId)
        Thread.sleep(200L)
        assertFalse(nodeM03.runtime.testIsSessionCapturing(m03SessionId))
        assertEquals(0, nodeM03.runtime.testInvariantF1BreakCount())
    }

    /** T2: follower grant then capture with local floor owner. */
    @Test
    fun followerGrant_enablesCaptureForOwner() {
        val channelId = "F1-GRANT"
        setupGroup(channelId)
        connectAnchorIce()
        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.pressPtt(m03SessionId)
        assertTrue(waitForFloorOwner(nodeM03, m03SessionId, nodeM03.localEndpoint.key))
        assertTrue(waitForCapturing(nodeM03, m03SessionId))
        val snap = nodeM03.runtime.sessionSnapshots().first { it.sessionId == m03SessionId }
        assertEquals(PttState.TALK, snap.localPttState)
        assertEquals(0, nodeM03.runtime.testInvariantF1BreakCount())
    }

    /** T3: authority grants remote — anchor does not capture, members observe remote owner. */
    @Test
    fun authorityGrantsRemote_anchorDoesNotCapture_listenerGetsGrant() {
        val channelId = "F1-REMOTE"
        setupGroup(channelId)
        connectAnchorIce()
        val m01SessionId = nodeM01.runtime.activeSessionIds().single()
        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.pressPtt(m03SessionId)
        assertTrue(waitForFloorOwner(nodeM01, m01SessionId, nodeM03.localEndpoint.key))
        assertTrue(waitForFloorOwner(nodeM02, m02SessionId(), nodeM03.localEndpoint.key))
        Thread.sleep(200L)
        assertFalse(nodeM01.runtime.testIsSessionCapturing(m01SessionId))
        assertEquals(0, nodeM01.runtime.testInvariantF1BreakCount())
    }

    /** T4: denied floor request must not capture. */
    @Test
    fun deniedFloorRequest_doesNotCapture() {
        val channelId = "F1-DENY"
        setupGroup(channelId)
        connectAnchorIce()
        val m01SessionId = nodeM01.runtime.activeSessionIds().single()
        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM01.pressPtt(m01SessionId)
        assertTrue(waitForCapturing(nodeM01, m01SessionId))
        nodeM03.pressPtt(m03SessionId)
        Thread.sleep(500L)
        assertFalse(nodeM03.runtime.testIsSessionCapturing(m03SessionId))
        val m03Snap = nodeM03.runtime.sessionSnapshots().first { it.sessionId == m03SessionId }
        assertTrue(m03Snap.floorOwnerKey != nodeM03.localEndpoint.key)
        assertEquals(0, nodeM03.runtime.testInvariantF1BreakCount())
        nodeM01.releasePtt(m01SessionId)
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

    private fun m02SessionId(): String = nodeM02.runtime.activeSessionIds().single()

    private fun waitForFloorOwner(node: TestTalkbackNode, sessionId: String, ownerKey: String): Boolean {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val snap = node.runtime.sessionSnapshots().firstOrNull { it.sessionId == sessionId }
            if (snap?.floorOwnerKey == ownerKey) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun waitForCapturing(node: TestTalkbackNode, sessionId: String): Boolean {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (node.runtime.testIsSessionCapturing(sessionId)) return true
            Thread.sleep(50)
        }
        return false
    }

    companion object {
        private const val ANCHOR_ID = "M01"
    }
}
