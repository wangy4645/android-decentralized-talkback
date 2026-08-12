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

/** #179-C — edge-ready bootstrap admission retry. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupBootstrapAdmissionEdgeReadyTest {

    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val hub = InMemorySignalingHub()

    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM02: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50621, m02 to 50622)
        nodeM01 = TestTalkbackNode(
            context = context,
            moduleId = m01,
            port = 50621,
            hub = hub,
            allPeers = peers,
            autoAcceptIncoming = false
        )
        nodeM02 = TestTalkbackNode(context, m02, 50622, hub, peers, autoAcceptIncoming = true)
        nodeM01.start()
        nodeM02.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
    }

    @Test
    fun edgeReadyAfterBlockedInvite_issuesBootstrapInviteWithoutGroupJoin() {
        val channelId = "BOOT-179C"
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
                it.contains("GROUP_BOOTSTRAP_INTENT_WAITING")
            }
        )
        assertFalse(nodeM01.hasLogSince(m01LogMark) { it.contains("Group invite sent ->") })

        nodeM01.runtime.testBlockPeerControlSignaling("M02", blocked = false)
        val retryMark = synchronized(nodeM01.logs) { nodeM01.logs.size }
        nodeM01.runtime.testNotifyPeerEdgeSignalingReady("M02")

        assertTrue(
            nodeM01.waitForLogSince(retryMark, timeoutMs = 5_000L) {
                it.contains("GROUP_BOOTSTRAP_EDGE_READY_EVALUATED") &&
                    it.contains("decision=ISSUE_INVITE")
            }
        )
        assertTrue(
            nodeM01.waitForLogSince(retryMark, timeoutMs = 5_000L) {
                it.contains("GROUP_BOOTSTRAP_EDGE_READY_INVITE_ISSUED")
            }
        )
        assertTrue(
            nodeM01.waitForLogSince(retryMark, timeoutMs = 5_000L) {
                it.contains("GROUP_BOOTSTRAP_INVITE_ISSUED")
            }
        )
        assertNoLogEventsSince(nodeM01, retryMark, "Group reconnect join offered")
        assertTrue(nodeM02.waitForLog(timeoutMs = 8_000L) { it.contains("invite accepted") })
        assertTrue(nodeM01.runtime.testIsCanonicalGroupMember(sessionId, "M02"))
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
