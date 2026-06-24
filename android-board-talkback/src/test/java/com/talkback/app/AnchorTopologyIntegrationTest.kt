package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.SessionType
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AnchorTopologyIntegrationTest {
    private val modules = (1..6).map { ModuleId("M%02d".format(it)) }
    private val hub = InMemorySignalingHub()
    private lateinit var nodes: List<TestTalkbackNode>

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = modules.mapIndexed { index, module ->
            module to (50020 + index + 1)
        }
        val allPeers = TestTalkbackNode.allPeers(*peers.toTypedArray())
        nodes = peers.map { (module, port) ->
            TestTalkbackNode(context, module, port, hub, allPeers, sharedSecret = "anchor-test")
        }
        nodes.forEach { it.start() }
    }

    @After
    fun tearDown() {
        nodes.forEach { it.stop() }
    }

    @Test
    fun sixModuleGroup_usesAnchorTopology() {
        val anchor = nodes.first()
        val remotes = modules.drop(1).map { EndpointAddress(it, EndpointId("E01")) }
        val sessionId = anchor.runtime.groupCall(
            anchor.localEndpoint,
            remotes,
            "ANCHOR-6"
        )
        assertNotNull(sessionId)
        assertTrue(nodes.drop(1).all { it.waitForLog { line -> line.contains("invite accepted") } })
        val snapshot = anchor.runtime.sessionSnapshots().first { it.sessionId == sessionId }
        assertEquals(SessionType.GROUP, snapshot.type)
        assertTrue(
            anchor.hasLog { it.contains("topology=ANCHOR") } ||
                anchor.runtime.sessionSnapshots().any { it.sessionId == sessionId }
        )
    }

    @Test
    fun fiveModuleGroup_staysMeshTopology() {
        val host = nodes.first()
        val remotes = modules.drop(1).take(4).map { EndpointAddress(it, EndpointId("E01")) }
        val sessionId = host.runtime.groupCall(host.localEndpoint, remotes, "MESH-5")
        assertNotNull(sessionId)
        assertTrue(host.hasLog { it.contains("topology=MESH") || it.contains("Group call") })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ChannelWarmupHostIntegrationTest {
    private val modules = (1..5).map { ModuleId("M%02d".format(it)) }
    private val hub = InMemorySignalingHub()
    private lateinit var nodes: List<TestTalkbackNode>

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = modules.mapIndexed { index, module -> module to (50120 + index + 1) }
        val allPeers = TestTalkbackNode.allPeers(*peers.toTypedArray())
        nodes = peers.map { (module, port) ->
            TestTalkbackNode(context, module, port, hub, allPeers)
        }
        nodes.forEach { it.start() }
    }

    @After
    fun tearDown() {
        nodes.forEach { it.stop() }
    }

    @Test
    fun fiveNodes_onlyMinHostInitiatesGroupCall() {
        val host = nodes.first()
        val remotes = modules.drop(1).map { EndpointAddress(it, EndpointId("E01")) }
        val sessionId = host.runtime.groupCall(host.localEndpoint, remotes, "WARMUP-5")
        assertNotNull(sessionId)
        assertTrue(nodes.drop(1).all { it.waitForLog { line -> line.contains("invite accepted") } })
        Thread.sleep(500)
        val nonHostInitiators = nodes.drop(1).count { it.hasLog { line -> line.contains("Group call initiated") } }
        assertEquals("Only min moduleId should initiate", 0, nonHostInitiators)
        assertFalse(
            nodes.any { it.hasLog { line -> line.contains("Replacing idle GROUP session") } }
        )
    }
}
