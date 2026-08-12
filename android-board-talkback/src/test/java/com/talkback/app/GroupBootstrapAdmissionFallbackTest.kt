package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #179-B — suppress GROUP_JOIN fallback while bootstrap admission intent is unresolved. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupBootstrapAdmissionFallbackTest {

    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val hub = InMemorySignalingHub()

    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM02: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50611, m02 to 50612)
        nodeM01 = TestTalkbackNode(
            context = context,
            moduleId = m01,
            port = 50611,
            hub = hub,
            allPeers = peers,
            autoAcceptIncoming = false
        )
        nodeM02 = TestTalkbackNode(context, m02, 50612, hub, peers, autoAcceptIncoming = false)
        nodeM01.start()
        nodeM02.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
    }

    @Test
    fun blockedInvite_retainsIntentAndSuppressesGroupJoinReconnect() {
        val channelId = "BOOT-179B"
        nodeM01.runtime.testBlockPeerControlSignaling("M02", blocked = true)
        val m01LogMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(EndpointAddress(m02, EndpointId("E01"))),
                channelId
            )
        )

        assertTrue(
            nodeM01.waitForLogSince(m01LogMark, timeoutMs = 5_000L) {
                it.contains("GROUP_BOOTSTRAP_INTENT_CREATED") && it.contains("peer=M02")
            }
        )
        assertTrue(
            nodeM01.waitForLogSince(m01LogMark, timeoutMs = 5_000L) {
                it.contains("GROUP_BOOTSTRAP_INTENT_WAITING") &&
                    it.contains("WAITING_EDGE_NOT_READY")
            }
        )
        assertFalse(
            nodeM01.hasLogSince(m01LogMark) { it.contains("Group invite sent ->") }
        )

        val reconnectMark = synchronized(nodeM01.logs) { nodeM01.logs.size }
        val reconnectSent = nodeM01.runtime.testTriggerGroupMeshReconnect(sessionId, "M02")
        assertTrue("reconnect path should be suppressed, not emit GROUP_JOIN", reconnectSent == 0)
        assertTrue(
            nodeM01.waitForLogSince(reconnectMark, timeoutMs = 2_000L) {
                it.contains("GROUP_BOOTSTRAP_FALLBACK_SUPPRESSED") &&
                    it.contains("reason=REMOTE_NO_SESSION")
            }
        )
        assertNoLogEventsSince(
            nodeM01,
            reconnectMark,
            "Group reconnect join offered"
        )
    }

    @Test
    fun remoteNoSessionJoin_ingressGateStillQueues() {
        val channelId = "BOOT-179B-INGRESS"
        val sessionId = "grp:orphan"
        val m02LogMark = synchronized(nodeM02.logs) { nodeM02.logs.size }

        nodeM01.runtime.testSendGroupJoinToPeer(
            targetPort = 50612,
            sessionId = sessionId,
            channelId = channelId,
            targetModuleId = "M02"
        )

        assertTrue(
            nodeM02.waitForLogSince(m02LogMark, timeoutMs = 5_000L) {
                it.contains("joinIngressDecision=QUEUED_NO_SESSION")
            }
        )
        assertTrue(nodeM02.runtime.testPendingGroupJoinCount(sessionId) > 0)
    }

    private fun TestTalkbackNode.hasLogSince(mark: Int, predicate: (String) -> Boolean): Boolean =
        synchronized(logs) { logs.drop(mark).any(predicate) }

    private fun assertNoLogEventsSince(node: TestTalkbackNode, mark: Int, vararg forbidden: String) {
        synchronized(node.logs) {
            val slice = node.logs.drop(mark)
            forbidden.forEach { pattern ->
                assertFalse(
                    "Unexpected log event containing '$pattern': ${slice.filter { it.contains(pattern) }}",
                    slice.any { it.contains(pattern) }
                )
            }
        }
    }
}
