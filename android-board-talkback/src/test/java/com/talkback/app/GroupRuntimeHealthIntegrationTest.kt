package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ADR-0008 issue #16: three-node GROUP PTT observes TopologySnapshot layering in logs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupRuntimeHealthIntegrationTest {
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
        val peers = TestTalkbackNode.allPeers(m01 to 50801, m02 to 50802, m03 to 50803)
        nodeM01 = TestTalkbackNode(context, m01, 50801, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50802, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50803, hub, peers)
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
    fun groupBootstrap_emitsAppStartAndFollowOnSnapshots() {
        setupThreeNodeGroup(CHANNEL_ID)
        assertTrue(
            nodeM01.waitForTopologySnapshot {
                it.contains("reason=APP_START") && it.contains("schemaVersion=1")
            } != null
        )
        assertTrue(nodeM01.topologySnapshotLines().size >= 2)
    }

    @Test
    fun partialTransmit_m01SnapshotsBuildingWithTransmitMissing() {
        setupThreeNodeGroup(CHANNEL_ID)
        nodeM01.runtime.simulateRemoteIceState("M02", "CONNECTED")
        val line = nodeM01.waitForTopologySnapshot {
            it.contains("groupTopologyReadiness=BUILDING") &&
                it.contains("transmitMissingPeers=M03")
        }
        assertNotNull(line)
        assertTrue(line!!.contains("meshMissingSignal="))
    }

    @Test
    fun fullTransmit_emitsOperationalWhenAllPeersConnected() {
        setupThreeNodeGroup(CHANNEL_ID)
        connectAllIceOnHost()
        val line = nodeM01.waitForTopologySnapshot {
            it.contains("groupTopologyReadiness=OPERATIONAL") &&
                it.contains("transmitReadyPeers=M02,M03")
        }
        assertNotNull(line)
        val transmitMissing = TopologySnapshotLogParser.field(line!!, "transmitMissingPeers")
        assertTrue(transmitMissing == null || transmitMissing.isEmpty())
    }

    @Test
    fun m03PartialIce_snapshotsBuildingLikeSyncingScenario() {
        setupThreeNodeGroup(CHANNEL_ID)
        settleAuthorityHello()
        nodeM03.runtime.simulateRemoteIceState("M01", "CONNECTED")
        val line = nodeM03.waitForTopologySnapshot(timeoutMs = 15_000L) {
            it.contains("localModuleId=M03") &&
                it.contains("transmitRequiredPeers=") &&
                it.contains("transmitMissingPeers=") &&
                !it.contains("transmitMissingPeers= ")
        }
        assertNotNull("snapshots=${nodeM03.topologySnapshotLines()}", line)
        assertTrue(line!!.contains("meshMissingIce="))
        assertTrue(
            line.contains("groupTopologyReadiness=BUILDING") ||
                line.contains("groupTopologyReadiness=MEMBERSHIP_PENDING")
        )
    }

    @Test
    fun iceStateChange_emitsTopologySnapshot() {
        setupThreeNodeGroup(CHANNEL_ID)
        connectAllIceOnHost()
        Thread.sleep(2_500L)
        val before = nodeM01.topologySnapshotLines().size
        nodeM01.runtime.simulateRemoteIceState("M03", "DISCONNECTED")
        assertNotNull(
            nodeM01.waitForTopologySnapshot(
                timeoutMs = 15_000L,
                minTotalLines = before
            ) {
                it.contains("reason=ICE_STATE_CHANGED")
            }
        )
    }

    private fun setupThreeNodeGroup(channelId: String) {
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
        settleAuthorityHello()
        Thread.sleep(300L)
        assertNotNull(sessionId)
    }

    private fun settleAuthorityHello() {
        nodeM01.runtime.upsertLocalEndpoint(
            nodeM01.localEndpoint.endpointId,
            "TestHandset",
            online = true
        )
        Thread.sleep(800L)
    }

    private fun connectAllIceOnHost() {
        nodeM01.runtime.simulateRemoteIceState("M02", "CONNECTED")
        nodeM01.runtime.simulateRemoteIceState("M03", "CONNECTED")
        Thread.sleep(200L)
    }

    companion object {
        private const val CHANNEL_ID = "GRP-HEALTH-RT"
    }
}

internal object TopologySnapshotLogParser {
    fun field(line: String, name: String): String? {
        val token = "$name="
        val idx = line.indexOf(token)
        if (idx < 0) return null
        val start = idx + token.length
        val end = line.indexOf(' ', start).let { if (it < 0) line.length else it }
        return line.substring(start, end).ifEmpty { null }
    }
}

internal fun TestTalkbackNode.topologySnapshotLines(): List<String> =
    synchronized(logs) { logs.filter { it.contains("TOPOLOGY_SNAPSHOT") } }

internal fun TestTalkbackNode.hasTopologySnapshot(predicate: (String) -> Boolean): Boolean =
    topologySnapshotLines().any(predicate)

internal fun TestTalkbackNode.waitForTopologySnapshot(
    timeoutMs: Long = 10_000L,
    minTotalLines: Int = 0,
    predicate: (String) -> Boolean
): String? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        synchronized(logs) {
            logs.filter { it.contains("TOPOLOGY_SNAPSHOT") }
                .drop(minTotalLines)
                .firstOrNull(predicate)
                ?.let { return it }
        }
        Thread.sleep(50)
    }
    return null
}
