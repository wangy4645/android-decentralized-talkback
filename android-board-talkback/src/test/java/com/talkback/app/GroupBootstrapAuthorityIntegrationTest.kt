package com.talkback.app

import com.talkback.core.discovery.FixedDiscoveryService
import com.talkback.core.discovery.ModulePresence
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
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

/** ADR-0053 phase 2 — reconcileGroupMeshInternal authority integration. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupBootstrapAuthorityIntegrationTest {

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
        val peers = TestTalkbackNode.allPeers(m01 to 50021, m02 to 50022, m03 to 50023)
        nodeM01 = TestTalkbackNode(context, m01, 50021, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50022, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50023, hub, peers)
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
    fun t4a_staleReconcileTick_invalidatesWithoutBootstrapEmission() {
        val channelId = "BOOT-AUTH-T4A"
        val sessionId = nodeM01.runtime.groupCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m02, EndpointId("E01"))),
            channelId
        )
        assertNotNull(sessionId)
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })

        nodeM02.runtime.testDropLocalGroupSession(sessionId!!)
        nodeM01.runtime.testSimulateGroupRosterMeshGap(sessionId, "M02")

        val m01LogMark = synchronized(nodeM01.logs) { nodeM01.logs.size }
        val m02LogMark = synchronized(nodeM02.logs) { nodeM02.logs.size }
        nodeM01.runtime.reconcileGroupMesh(channelId)

        assertTrue(
            nodeM01.waitForLogSince(m01LogMark, timeoutMs = 3_000L) {
                it.contains("GROUP_AUTHORITY_INVALIDATED")
            }
        )
        assertNull(
            "STALE tick must not re-establish GROUP session",
            nodeM01.runtime.sessionSnapshotForChannel(channelId)
        )
        assertFalse(
            nodeM02.waitForLogSince(m02LogMark, timeoutMs = 500L) {
                it.contains("Group invite")
            }
        )

        val deadline = System.currentTimeMillis() + 8_000L
        var bootstrapped = false
        while (System.currentTimeMillis() < deadline) {
            nodeM01.runtime.reconcileGroupMesh(channelId)
            if (nodeM01.runtime.sessionSnapshotForChannel(channelId) != null) {
                bootstrapped = true
                break
            }
            Thread.sleep(50L)
        }
        assertTrue("subsequent reconcile ticks should bootstrap GROUP session", bootstrapped)
    }

    @Test
    fun t2_stalePrimaryWithPendingJoin_invalidatesAndRebootstraps() {
        val channelId = "BOOT-AUTH-T2"
        val firstSessionId = nodeM01.runtime.groupCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m02, EndpointId("E01"))),
            channelId
        )
        assertNotNull(firstSessionId)
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })

        nodeM02.runtime.testDropLocalGroupSession(firstSessionId!!)
        nodeM01.runtime.testSimulateGroupRosterMeshGap(firstSessionId, "M02")

        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }
        nodeM01.runtime.reconcileGroupMesh(channelId)

        assertTrue(
            nodeM01.waitForLogSince(logMark, timeoutMs = 8_000L) {
                it.contains("GROUP_AUTHORITY_INVALIDATED")
            }
        )
        val deadline = System.currentTimeMillis() + 8_000L
        var recovered = false
        while (System.currentTimeMillis() < deadline) {
            val snap = nodeM01.runtime.sessionSnapshotForChannel(channelId)
            if (snap != null && snap.sessionId == firstSessionId) {
                recovered = true
                break
            }
            nodeM01.runtime.reconcileGroupMesh(channelId)
            Thread.sleep(50L)
        }
        assertTrue("GROUP session should be re-established on channel", recovered)
        assertTrue(
            nodeM02.waitForLog(timeoutMs = 8_000L) {
                it.contains("invite accepted") || it.contains("Group invite")
            }
        )
    }

    @Test
    fun t5_dialableStalePrimary_followerDoesNotBootstrap() {
        val channelId = "BOOT-AUTH-T5"
        val sessionId = nodeM01.runtime.groupCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m02, EndpointId("E01"))),
            channelId
        )
        assertNotNull(sessionId)
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })

        nodeM02.runtime.hangup(sessionId!!)
        nodeM01.runtime.testSimulateGroupRosterMeshGap(sessionId, "M02")
        nodeM01.runtime.reconcileGroupMesh(channelId)

        val m02LogMark = synchronized(nodeM02.logs) { nodeM02.logs.size }
        nodeM02.runtime.reconcileGroupMesh(channelId)
        Thread.sleep(500L)
        assertFalse(
            nodeM02.waitForLogSince(m02LogMark, timeoutMs = 1_000L) {
                it.contains("mesh_create")
            }
        )
        assertTrue(
            nodeM02.waitForLogSince(m02LogMark, timeoutMs = 3_000L) {
                it.contains("Waiting for primary")
            }
        )
    }

    @Test
    fun t6_primaryAbsentFromDialable_followerMayBootstrap() {
        val channelId = "BOOT-AUTH-T6"
        val context = RuntimeEnvironment.getApplication()
        val peersM02Only = TestTalkbackNode.allPeers(m02 to 50022, m03 to 50023)
        val isolatedM02 = TestTalkbackNode(
            context = context,
            moduleId = m02,
            port = 50022,
            hub = hub,
            allPeers = peersM02Only,
            discoveryService = FixedDiscoveryService(m02, peersM02Only)
        )
        val isolatedM03 = TestTalkbackNode(
            context = context,
            moduleId = m03,
            port = 50023,
            hub = hub,
            allPeers = peersM02Only,
            discoveryService = FixedDiscoveryService(m03, peersM02Only)
        )
        isolatedM02.start()
        isolatedM03.start()
        Thread.sleep(500L)
        try {
            isolatedM02.runtime.reconcileGroupMesh(channelId)
            val deadline = System.currentTimeMillis() + 8_000L
            var sessionId: String? = null
            while (System.currentTimeMillis() < deadline) {
                sessionId = isolatedM02.runtime.sessionSnapshotForChannel(channelId)?.sessionId
                if (sessionId != null) break
                isolatedM02.runtime.reconcileGroupMesh(channelId)
                Thread.sleep(50L)
            }
            assertNotNull(sessionId)
        } finally {
            isolatedM02.stop()
            isolatedM03.stop()
        }
    }
}
