package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupMediaLinkLossSessionRetentionTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val m04 = ModuleId("M04")
    private val hub = InMemorySignalingHub()
    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM02: TestTalkbackNode
    private lateinit var nodeM03: TestTalkbackNode
    private lateinit var nodeM04: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(
            m01 to 50001,
            m02 to 50002,
            m03 to 50003,
            m04 to 50004
        )
        nodeM01 = TestTalkbackNode(context, m01, 50001, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50002, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50003, hub, peers)
        nodeM04 = TestTalkbackNode(context, m04, 50004, hub, peers)
        listOf(nodeM01, nodeM02, nodeM03, nodeM04).forEach { it.start() }
    }

    @After
    fun tearDown() {
        listOf(nodeM04, nodeM03, nodeM02, nodeM01).forEach { it.stop() }
    }

    @Test
    fun fourMember_sequentialEdgeLoss_retainsSession() {
        val channelId = "CH-RET-4"
        val sessionId = bootstrapFourMemberGroup(channelId)
        connectGroupIce(nodeM02, listOf("M01", "M03", "M04"))

        val logMark = synchronized(nodeM02.logs) { nodeM02.logs.size }
        nodeM02.runtime.simulateRemoteIceState("M01", "DISCONNECTED")
        Thread.sleep(200L)
        nodeM02.runtime.simulateRemoteIceState("M04", "DISCONNECTED")
        Thread.sleep(200L)
        nodeM02.runtime.simulateRemoteIceState("M03", "DISCONNECTED")
        Thread.sleep(300L)

        assertSessionRetained(nodeM02, sessionId, logMark)
        assertCanonicalRosterContains(nodeM02, sessionId, "M01", "M02", "M03", "M04")
    }

    @Test
    fun twoMember_remoteEdgeLoss_retainsSession() {
        val channelId = "CH-RET-2"
        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(EndpointAddress(m02, EndpointId("E01"))),
                channelId
            )
        ) { "groupCall blocked" }
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        Thread.sleep(2_000L)
        connectGroupIce(nodeM01, listOf("M02"))
        connectGroupIce(nodeM02, listOf("M01"))

        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }
        nodeM01.runtime.simulateRemoteIceState("M02", "DISCONNECTED")
        Thread.sleep(300L)

        assertSessionRetained(nodeM01, sessionId, logMark)
        assertCanonicalRosterContains(nodeM01, sessionId, "M01", "M02")
    }

    @Test
    fun mediaMapShrink_secondEdgeLoss_retainsSession() {
        val channelId = "CH-RET-SHRINK"
        val sessionId = bootstrapFourMemberGroup(channelId)
        connectGroupIce(nodeM02, listOf("M01", "M03", "M04"))

        val logMark = synchronized(nodeM02.logs) { nodeM02.logs.size }
        nodeM02.runtime.simulateRemoteIceState("M01", "DISCONNECTED")
        Thread.sleep(200L)
        assertTrue(
            nodeM02.waitForLogSince(logMark) {
                it.contains("Group media link lost for M01, marking SUSPECT")
            }
        )
        nodeM02.runtime.simulateRemoteIceState("M04", "DISCONNECTED")
        Thread.sleep(200L)
        nodeM02.runtime.simulateRemoteIceState("M03", "DISCONNECTED")
        Thread.sleep(300L)

        assertSessionRetained(nodeM02, sessionId, logMark)
        assertFalse(
            logsSince(nodeM02, logMark).any { it.contains("Media link lost for M03, tearing down session") }
        )
    }

    @Test
    fun unrelatedHealthyEdgeRemains_afterPeerLoss() {
        val channelId = "CH-RET-HEALTHY"
        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(
                    EndpointAddress(m02, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
        ) { "groupCall blocked" }
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        Thread.sleep(2_000L)
        connectGroupIce(nodeM01, listOf("M02", "M03"))

        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }
        nodeM01.runtime.simulateRemoteIceState("M02", "DISCONNECTED")
        Thread.sleep(300L)

        assertSessionRetained(nodeM01, sessionId, logMark)
        val logs = logsSince(nodeM01, logMark)
        assertFalse(logs.any { it.contains("Group media link lost for M03") })
        assertFalse(logs.any { it.contains("PC_CLOSE_ENTER module=M03") })
        nodeM01.runtime.simulateRemoteIceState("M03", "CONNECTED")
        Thread.sleep(200L)
        assertCanonicalRosterContains(nodeM01, sessionId, "M01", "M02", "M03")
    }

    private fun bootstrapFourMemberGroup(channelId: String): String {
        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(
                    EndpointAddress(m02, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01")),
                    EndpointAddress(m04, EndpointId("E01"))
                ),
                channelId
            )
        ) { "groupCall blocked" }
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        assertTrue(nodeM04.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        Thread.sleep(2_000L)
        return sessionId
    }

    private fun connectGroupIce(node: TestTalkbackNode, remoteModuleIds: List<String>) {
        remoteModuleIds
            .filter { it != node.moduleId.value }
            .forEach { node.runtime.simulateRemoteIceState(it, "CONNECTED") }
        Thread.sleep(200L)
    }

    private fun assertSessionRetained(node: TestTalkbackNode, sessionId: String, logMark: Int) {
        assertEquals(listOf(sessionId), node.runtime.activeSessionIds())
        val logs = logsSince(node, logMark)
        assertFalse(logs.any { it.contains("tearing down session") })
        assertFalse(logs.any { it.contains("] Hangup") })
        assertTrue(logs.any { it.contains("Group media link lost for") && it.contains("marking SUSPECT") })
    }

    private fun assertCanonicalRosterContains(
        node: TestTalkbackNode,
        sessionId: String,
        vararg moduleIds: String
    ) {
        val members = node.runtime.testGroupMemberModuleIds(sessionId).toSet()
        moduleIds.forEach { assertTrue("$sessionId roster missing $it: $members", members.contains(it)) }
    }

    private fun logsSince(node: TestTalkbackNode, mark: Int): List<String> =
        synchronized(node.logs) { node.logs.drop(mark) }
}
