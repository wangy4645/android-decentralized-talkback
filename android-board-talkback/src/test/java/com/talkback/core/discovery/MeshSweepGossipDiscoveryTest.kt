package com.talkback.core.discovery

import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.InMemoryDiscoveryHub
import com.talkback.core.signaling.InMemoryDiscoveryTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class MeshSweepGossipDiscoveryTest {
    private val hub = InMemoryDiscoveryHub()
    private val discoveryPort = 51999
    private val secret = "task-secret-alpha"
    private val nodes = mutableListOf<Node>()

    @Before
    fun setUp() {
        nodes += createNode(ModuleId("M01"), 50000, "127.0.0.1")
        nodes += createNode(ModuleId("M02"), 50001, "127.0.0.1")
        nodes += createNode(ModuleId("M03"), 50002, "127.0.0.1")
        nodes.forEach { it.discovery.start(it.moduleId, it.signalingPort) }
    }

    @After
    fun tearDown() {
        nodes.forEach { it.discovery.stop() }
        nodes.clear()
    }

    @Test
    fun gossipConvergesToAllPeersWithoutStaticConfig() {
        nodes[0].discovery.resetAndSweep()
        waitUntil {
            val roster = nodes[0].presence.map { it.moduleId.value }.toSet()
            roster.contains("M02") && roster.contains("M03")
        }
        val m01Roster = nodes[0].presence.map { it.moduleId.value }.toSet()
        assertTrue(m01Roster.contains("M02"))
        assertTrue(m01Roster.contains("M03"))
    }

    @Test
    fun pexSpreadsThirdPeerAfterSingleContact() {
        val m03 = nodes[2]
        m03.discovery.stop()
        nodes[0].discovery.resetAndSweep()
        waitUntil { nodes[0].presence.any { it.moduleId.value == "M02" } }
        val self = DiscoveryPeerEntry(ModuleId("M02"), "127.0.0.1", 50001, 1)
        val known = listOf(DiscoveryPeerEntry(ModuleId("M03"), "127.0.0.1", 50002, 1))
        nodes[1].discovery.ingestVerifiedAnnounce(self, known, "127.0.0.1")
        nodes[0].discovery.resetAndSweep()
        waitUntil(timeoutMs = 8_000) {
            nodes[0].presence.any { it.moduleId.value == "M03" }
        }
        assertTrue(nodes[0].presence.any { it.moduleId.value == "M03" })
    }

    @Test
    fun bootstrapSweep_retriesQuicklyWhileRosterEmpty() {
        nodes.forEach { it.discovery.stop() }
        nodes.clear()
        val presence = CopyOnWriteArrayList<ModulePresence>()
        val transport = InMemoryDiscoveryTransport(hub, "127.0.0.1", discoveryPort)
        val discovery = MeshSweepGossipDiscovery(
            sharedSecret = { secret },
            subnetProvider = FixedSubnetHostProvider("127.0.0.1", listOf("127.0.0.1")),
            transport = transport,
            config = MeshSweepGossipConfig(
                discoveryPort = discoveryPort,
                sweepPacketDelayMs = 0L,
                bootstrapSweepIntervalMs = 200L,
                bootstrapDurationMs = 5_000L,
                sweepBackoffMs = longArrayOf(30_000L)
            )
        )
        discovery.onPresenceChanged { list -> presence.clear(); presence.addAll(list) }
        discovery.start(ModuleId("M01"), 50000)
        try {
            val deadline = System.currentTimeMillis() + 1_500L
            while (System.currentTimeMillis() < deadline && discovery.sweepCount < 2) {
                Thread.sleep(50L)
            }
            assertTrue(
                "Expected at least 2 sweeps within bootstrap interval, got ${discovery.sweepCount}",
                discovery.sweepCount >= 2
            )
        } finally {
            discovery.stop()
        }
    }

    @Test
    fun differentSecretsDoNotMergeRoster() {
        nodes.forEach { it.discovery.stop() }
        nodes.clear()
        val a = createNode(ModuleId("M01"), 50000, "127.0.0.1", "secret-a")
        val b = createNode(ModuleId("M02"), 50001, "127.0.0.1", "secret-b")
        a.discovery.start(a.moduleId, a.signalingPort)
        b.discovery.start(b.moduleId, b.signalingPort)
        a.discovery.resetAndSweep()
        Thread.sleep(500)
        assertEquals(0, a.presence.size)
        a.discovery.stop()
        b.discovery.stop()
    }

    private fun createNode(
        moduleId: ModuleId,
        signalingPort: Int,
        host: String,
        sharedSecret: String = secret
    ): Node {
        val presence = CopyOnWriteArrayList<ModulePresence>()
        val transport = InMemoryDiscoveryTransport(hub, host, discoveryPort)
        val discovery = MeshSweepGossipDiscovery(
            sharedSecret = { sharedSecret },
            subnetProvider = FixedSubnetHostProvider(host, listOf(host)),
            transport = transport,
            config = MeshSweepGossipConfig(
                discoveryPort = discoveryPort,
                sweepPacketDelayMs = 0L,
                announceIntervalMs = 100L,
                ttlCleanupIntervalMs = 60_000L,
                sweepBackoffMs = longArrayOf(200L, 400L)
            )
        )
        discovery.onPresenceChanged { list -> presence.clear(); presence.addAll(list) }
        return Node(moduleId, signalingPort, discovery, presence)
    }

    private fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }

    private data class Node(
        val moduleId: ModuleId,
        val signalingPort: Int,
        val discovery: MeshSweepGossipDiscovery,
        val presence: CopyOnWriteArrayList<ModulePresence>
    )
}
