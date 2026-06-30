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

/**
 * ADR-0009 issue #20: cold M03 join with placeholder invite endpoint → first Group PTT
 * must not observe ROSTER_MISS; grant uses canonical M03-E03.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupIdentityColdM03IntegrationTest {
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
        val peers = TestTalkbackNode.allPeers(m01 to 50901, m02 to 50902, m03 to 50903)
        nodeM01 = TestTalkbackNode(context, m01, 50901, hub, peers, heartbeatIntervalMs = 500L)
        nodeM02 = TestTalkbackNode(context, m02, 50902, hub, peers, heartbeatIntervalMs = 500L)
        nodeM03 = TestTalkbackNode(context, m03, 50903, hub, peers, heartbeatIntervalMs = 500L)
        nodeM01.start()
        nodeM02.start()
        nodeM03.start()
        configureColdM03Endpoint()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        nodeM03.stop()
    }

    @Test
    fun coldM03Join_firstPtt_noRosterMiss_grantUsesCanonicalEndpoint() {
        setupThreeNodeGroupWithPlaceholderInvite()
        connectAllIce()
        waitForIdentityReconcile()

        val m02SessionId = nodeM02.runtime.activeSessionIds().single()
        val m03SessionId = nodeM03.runtime.activeSessionIds().single()

        nodeM03.pressPtt(m03SessionId)

        assertTrue(
            "M03 floor request logs=${nodeM03.floorRequestLines()}",
            nodeM03.waitForLog(timeoutMs = 5_000L) {
                it.contains("FLOOR_REQUEST_SEND") && !it.contains("identity_unstable")
            }
        )
        assertTrue(
            "M02 floor owner=${nodeM02.runtime.sessionSnapshots().firstOrNull { it.sessionId == m02SessionId }?.protocolFloorOwnerKey}",
            waitForFloorOwner(nodeM02, m02SessionId, CANONICAL_M03_KEY)
        )
        assertFalse(
            "M02 logs=${nodeM02.grantObserveLines()}",
            nodeM02.hasGrantRosterMiss()
        )
        assertTrue(
            nodeM02.hasLog {
                it.contains("GRANT_OBSERVED") &&
                    it.contains("requesterKey=$CANONICAL_M03_KEY") &&
                    it.contains("resolved=OK")
            }
        )
        assertTrue(
            nodeM01.hasLog { it.contains("GRANT_BROADCAST") && it.contains(CANONICAL_M03_KEY) }
        )
    }

    @Test
    fun coldM03Join_membershipDigestAlignedBeforeFirstPtt() {
        setupThreeNodeGroupWithPlaceholderInvite()
        connectAllIce()
        waitForIdentityReconcile()
        val aligned = nodeM02.waitForTopologySnapshot(timeoutMs = 15_000L) {
            TopologySnapshotLogParser.field(it, "membershipDigestAligned") == "true"
        }
        assertNotNull("snapshots=${nodeM02.topologySnapshotLines()}", aligned)
    }

    private fun configureColdM03Endpoint() {
        nodeM03.runtime.upsertLocalEndpoint(EndpointId("E01"), "TestHandset", online = false)
        nodeM03.runtime.upsertLocalEndpoint(EndpointId("E03"), "ColdHandset", online = true)
    }

    private fun setupThreeNodeGroupWithPlaceholderInvite() {
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
        settleAuthorityHello()
        Thread.sleep(400L)
        assertNotNull(sessionId)
    }

    private fun settleAuthorityHello() {
        nodeM01.runtime.upsertLocalEndpoint(
            nodeM01.localEndpoint.endpointId,
            "TestHandset",
            online = true
        )
        Thread.sleep(600L)
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

    private fun waitForIdentityReconcile() {
        assertTrue(
            nodeM01.waitForLog(timeoutMs = 12_000L) {
                it.contains("IDENTITY_REBOUND") && it.contains(CANONICAL_M03_KEY)
            }
        )
        listOf(nodeM02, nodeM03).forEach { node ->
            assertTrue(
                "${node.localEndpoint} missing identity settle",
                node.waitForLog(timeoutMs = 12_000L) {
                    it.contains("IDENTITY_REBOUND") || it.contains("snapshotApplied")
                }
            )
            assertNotNull(
                "${node.localEndpoint} digest snapshots=${node.topologySnapshotLines()}",
                node.waitForTopologySnapshot(timeoutMs = 15_000L) {
                    TopologySnapshotLogParser.field(it, "membershipDigestAligned") == "true"
                }
            )
        }
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

    private fun TestTalkbackNode.floorRequestLines(): List<String> =
        synchronized(logs) {
            logs.filter {
                it.contains("FLOOR_REQUEST") || it.contains("PTT_GATE") || it.contains("TRY_GRANT")
            }
        }

    private fun TestTalkbackNode.grantObserveLines(): List<String> =
        synchronized(logs) { logs.filter { it.contains("GRANT_OBSERVED") || it.contains("GRANT_DROPPED") } }

    private fun TestTalkbackNode.hasGrantRosterMiss(): Boolean =
        synchronized(logs) {
            logs.any {
                (it.contains("GRANT_OBSERVED") && it.contains("ROSTER_MISS")) ||
                    (it.contains("GRANT_DROPPED") && it.contains("ROSTER_MISS"))
            }
        }

    companion object {
        private const val CHANNEL_ID = "COLD-M03-PTT"
        private const val CANONICAL_M03_KEY = "M03-E03"
    }
}
