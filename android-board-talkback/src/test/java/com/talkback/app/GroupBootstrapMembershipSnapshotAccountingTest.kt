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

/**
 * Finding-1 (#179): membership-snapshot GROUP_INVITE must not satisfy bootstrap SDP invite accounting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupBootstrapMembershipSnapshotAccountingTest {

    private val m01 = ModuleId("M01")
    private val m03 = ModuleId("M03")
    private val hub = InMemorySignalingHub()

    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM03: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50641, m03 to 50643)
        nodeM01 = TestTalkbackNode(
            context = context,
            moduleId = m01,
            port = 50641,
            hub = hub,
            allPeers = peers,
            autoAcceptIncoming = false
        )
        nodeM03 = TestTalkbackNode(context, m03, 50643, hub, peers, autoAcceptIncoming = false)
        nodeM01.start()
        nodeM03.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM03.stop()
    }

    @Test
    fun membershipSnapshot_doesNotBootstrapAccounting_edgeReadyRetryStillEligible() {
        val channelId = "BOOT-SNAPSHOT-ACC"
        nodeM01.runtime.testBlockPeerControlSignaling("M03", blocked = true)
        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(EndpointAddress(m03, EndpointId("E03"))),
                channelId
            )
        )

        assertTrue(
            nodeM01.waitForLogSince(logMark, timeoutMs = 5_000L) {
                it.contains("GROUP_BOOTSTRAP_INTENT_WAITING") && it.contains("peer=M03")
            }
        )
        assertEquals("WAITING_EDGE_READY", nodeM01.runtime.testBootstrapAdmissionIntentState(channelId, "M03"))

        nodeM01.runtime.testBlockPeerControlSignaling("M03", blocked = false)
        val snapshotMark = synchronized(nodeM01.logs) { nodeM01.logs.size }
        assertTrue(nodeM01.runtime.testSendMembershipSnapshotInvite(sessionId, "M03"))

        assertFalse(
            nodeM01.hasLogSince(snapshotMark) {
                it.contains("GROUP_BOOTSTRAP_INVITE_ISSUED") && it.contains("peer=M03")
            }
        )
        assertEquals("WAITING_EDGE_READY", nodeM01.runtime.testBootstrapAdmissionIntentState(channelId, "M03"))
        assertEquals(
            "ISSUE_INVITE",
            nodeM01.runtime.testPreviewBootstrapEdgeReadyRetry(channelId, "M03")
        )
    }

    private fun TestTalkbackNode.hasLogSince(mark: Int, predicate: (String) -> Boolean): Boolean =
        synchronized(logs) { logs.drop(mark).any(predicate) }
}
