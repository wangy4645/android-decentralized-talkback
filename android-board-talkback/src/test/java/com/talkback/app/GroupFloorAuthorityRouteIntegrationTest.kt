package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.ptt.FloorRouteDecision
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
 * ADR-0013: stale [TalkbackSession.remotePeersByModule] must not steer floor requests away from authority.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupFloorAuthorityRouteIntegrationTest {
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
        val peers = TestTalkbackNode.allPeers(m01 to 50701, m02 to 50702, m03 to 50703)
        nodeM01 = TestTalkbackNode(context, m01, 50701, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50702, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50703, hub, peers)
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
    fun staleRemotePeerTable_m03Ptt_reachesAuthorityNotSecondary() {
        setupGroup()
        connectAllIce()
        settleGroup()

        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.runtime.testCorruptRemotePeer(
            m03SessionId,
            m01.value,
            TestTalkbackNode.peerTarget(50702)
        )

        nodeM03.pressPtt(m03SessionId)

        assertTrue(
            "M01 logs=${authorityObserveLines(nodeM01)}",
            nodeM01.waitForLog(timeoutMs = 8_000L) {
                it.contains("REQUEST_OBSERVED") && it.contains("from=M03")
            }
        )
        assertTrue(
            "M03 route logs=${routeLines(nodeM03)}",
            nodeM03.waitForLog(timeoutMs = 8_000L) {
                it.contains("FLOOR_ROUTE_DECISION") && it.contains("result=ROUTE_OK")
            }
        )
        assertFalse(
            "M02 must not arbitrate M03 request logs=${authorityObserveLines(nodeM02)}",
            nodeM02.waitForLog(timeoutMs = 1_000L) {
                it.contains("TRY_GRANT") && it.contains("requester=M03")
            }
        )
        assertFalse(
            "M02 must not observe M03 floor request logs=${authorityObserveLines(nodeM02)}",
            nodeM02.hasLog {
                it.contains("REQUEST_OBSERVED") && it.contains("from=M03")
            }
        )

        nodeM03.releasePtt(m03SessionId)
    }

    @Test
    fun pollutedLegacySignalPeer_m03Ptt_stillReachesAuthorityViaRegistry() {
        setupGroup()
        connectAllIce()
        settleGroup()

        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.runtime.testCorruptSignalPeer(m01.value, TestTalkbackNode.peerTarget(50702))

        nodeM03.pressPtt(m03SessionId)

        assertTrue(
            "M01 logs=${authorityObserveLines(nodeM01)}",
            nodeM01.waitForLog(timeoutMs = 8_000L) {
                it.contains("REQUEST_OBSERVED") && it.contains("from=M03")
            }
        )
        assertTrue(
            "M03 route logs=${routeLines(nodeM03)}",
            nodeM03.waitForLog(timeoutMs = 8_000L) {
                it.contains("FLOOR_ROUTE_DECISION") && it.contains("result=ROUTE_OK")
            }
        )
        assertFalse(
            "M02 must not arbitrate M03 request logs=${authorityObserveLines(nodeM02)}",
            nodeM02.waitForLog(timeoutMs = 1_000L) {
                it.contains("TRY_GRANT") && it.contains("requester=M03")
            }
        )

        nodeM03.releasePtt(m03SessionId)
    }

    @Test
    fun m03Ptt_threeNodeGrantAndRelease_endToEnd() {
        setupGroup()
        connectAllIce()
        settleGroup()

        val m01SessionId = nodeM01.runtime.activeSessionIds().single()
        val m03SessionId = nodeM03.runtime.activeSessionIds().single()

        nodeM03.pressPtt(m03SessionId)

        assertTrue(
            "M01 must observe M03 request logs=${authorityObserveLines(nodeM01)}",
            nodeM01.waitForLog(timeoutMs = 8_000L) {
                it.contains("REQUEST_OBSERVED") && it.contains("from=M03")
            }
        )
        assertTrue(
            "M03 route logs=${routeLines(nodeM03)}",
            nodeM03.waitForLog(timeoutMs = 8_000L) {
                it.contains("FLOOR_ROUTE_DECISION") &&
                    it.contains("result=ROUTE_OK") &&
                    it.contains("resolvedFrom=${FloorRouteDecision.RESOLVED_FROM}")
            }
        )
        assertTrue(
            "M03 floor owner logs=${nodeM03.runtime.sessionSnapshots()}",
            waitForFloorOwner(nodeM03, m03SessionId, nodeM03.localEndpoint.key)
        )
        assertTrue(
            "M01 sees remote floor holder logs=${nodeM01.runtime.sessionSnapshots()}",
            waitForFloorOwner(nodeM01, m01SessionId, nodeM03.localEndpoint.key)
        )
        val m03Snap = nodeM03.runtime.sessionSnapshots().first { it.sessionId == m03SessionId }
        assertEquals(PttState.TALK, m03Snap.localPttState)

        nodeM03.releasePtt(m03SessionId)
        Thread.sleep(400L)
        val m03AfterRelease = nodeM03.runtime.sessionSnapshots().first { it.sessionId == m03SessionId }
        assertTrue(m03AfterRelease.localPttState != PttState.TALK)
    }

    private fun waitForFloorOwner(node: TestTalkbackNode, sessionId: String, ownerKey: String): Boolean {
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            val snap = node.runtime.sessionSnapshots().firstOrNull { it.sessionId == sessionId }
            if (snap?.protocolFloorOwnerKey == ownerKey) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test
    fun routeInvalid_whenTransportBindingMissing_doesNotSendToSecondary() {
        setupGroup()
        connectAllIce()
        settleGroup()

        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.runtime.testCorruptRemotePeer(
            m03SessionId,
            m01.value,
            TestTalkbackNode.peerTarget(50702)
        )
        nodeM03.runtime.testClearTransportBinding(m01.value)

        nodeM03.pressPtt(m03SessionId)

        assertTrue(
            nodeM03.waitForLog(timeoutMs = 5_000L) {
                it.contains("FLOOR_ROUTE_DECISION") && it.contains("result=ROUTE_INVALID")
            }
        )
        assertFalse(
            nodeM01.hasLog { it.contains("REQUEST_OBSERVED") && it.contains("from=M03") }
        )
        assertFalse(
            nodeM02.hasLog { it.contains("REQUEST_OBSERVED") && it.contains("from=M03") }
        )
    }

    private fun setupGroup() {
        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(
                    EndpointAddress(m02, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                CHANNEL_ID
            )
        )
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        listOf(nodeM01, nodeM02, nodeM03).forEach {
            it.runtime.testForceGroupAnchorTopology(CHANNEL_ID)
        }
        Thread.sleep(400L)
        assertNotNull(sessionId)
    }

    private fun connectAllIce() {
        nodeM01.runtime.simulateRemoteIceState("M02", "CONNECTED")
        nodeM01.runtime.simulateRemoteIceState("M03", "CONNECTED")
        nodeM02.runtime.simulateRemoteIceState("M01", "CONNECTED")
        nodeM02.runtime.simulateRemoteIceState("M03", "CONNECTED")
        nodeM03.runtime.simulateRemoteIceState("M01", "CONNECTED")
        nodeM03.runtime.simulateRemoteIceState("M02", "CONNECTED")
        Thread.sleep(300L)
    }

    private fun settleGroup() {
        assertNotNull(
            nodeM01.waitForTopologySnapshot(timeoutMs = 15_000L) {
                TopologySnapshotLogParser.field(it, "membershipDigestAligned") == "true"
            }
        )
        Thread.sleep(300L)
    }

    private fun authorityObserveLines(node: TestTalkbackNode): List<String> =
        synchronized(node.logs) {
            node.logs.filter { it.contains("REQUEST_OBSERVED") || it.contains("TRY_GRANT") }
        }

    private fun routeLines(node: TestTalkbackNode): List<String> =
        synchronized(node.logs) {
            node.logs.filter { it.contains("FLOOR_ROUTE") || it.contains("FLOOR_REQUEST_SEND") }
        }

    companion object {
        private const val CHANNEL_ID = "FLOOR-ROUTE-ADR13"
    }
}
