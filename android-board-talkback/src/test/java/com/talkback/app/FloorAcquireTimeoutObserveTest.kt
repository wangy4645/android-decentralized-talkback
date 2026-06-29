package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.ptt.PttState
import com.talkback.core.signaling.InMemorySignalingHub
import com.talkback.core.util.PttTimingLog
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
 * ADR-0004 Phase 1–2: observe + config only — timeout must not trigger FLOOR_RELEASE yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class FloorAcquireTimeoutObserveTest {
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

    @Test
    fun startup_logsAcquireReleaseTimeoutConfig() {
        assertTrue(
            nodeM03.hasLog {
                it.contains("acquireReleaseTimeoutMs=500") && it.contains("observe-only")
            }
        )
        assertEquals(500L, nodeM03.runtime.acquireReleaseTimeoutMs())
    }

    @Test
    fun grantWithoutCapture_pastInterimTimeout_doesNotAutoRelease() {
        val channelId = "ACQ-TIMEOUT-OBS"
        setupGroup(channelId)
        val sessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.pressPtt(sessionId)
        assertTrue(waitForProtocolOwner(nodeM03, sessionId, nodeM03.localEndpoint.key))
        assertFalse(nodeM03.runtime.modulePresenceSnapshot().localUplinkGrant)

        Thread.sleep(650L)

        val presence = requireNotNull(nodeM03.runtime.sessionPresenceSnapshot(sessionId))
        assertEquals(nodeM03.localEndpoint.key, presence.protocolFloorOwnerKey)
        assertFalse(nodeM03.runtime.testIsSessionCapturing(sessionId))
        val snap = nodeM03.runtime.sessionSnapshots().first { it.sessionId == sessionId }
        assertTrue(snap.localPttState == PttState.TALK || snap.localPttState == PttState.REQUEST_FLOOR)
        assertFalse(nodeM03.hasLog { it.contains("FLOOR_RELEASE_DIAG") })
    }

    @Test
    fun grantWithIce_recordsSinceGrantSample() {
        PttTimingLog.resetForTest()
        val channelId = "ACQ-SINCE-GRANT"
        setupGroup(channelId)
        connectAnchorIce()
        val sessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.pressPtt(sessionId)
        assertTrue(waitForCapturing(nodeM03, sessionId))
        val sample = requireNotNull(PttTimingLog.lastSampleForTest())
        assertTrue("sinceGrant must be non-negative", sample.first >= 0L)
        assertFalse("first capture in session is cold-start", sample.second)
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

    private fun waitForProtocolOwner(
        node: TestTalkbackNode,
        sessionId: String,
        ownerKey: String
    ): Boolean {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val snap = node.runtime.sessionPresenceSnapshot(sessionId)
            if (snap?.protocolFloorOwnerKey == ownerKey) return true
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
