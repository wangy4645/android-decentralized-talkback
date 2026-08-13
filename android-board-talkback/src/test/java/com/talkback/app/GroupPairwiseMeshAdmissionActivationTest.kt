package com.talkback.app

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

/** #180-C — unsatisfied pairwise obligation activates SDP GROUP_INVITE on offerer only. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupPairwiseMeshAdmissionActivationTest {

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
        val peers = TestTalkbackNode.allPeers(m01 to 50631, m02 to 50632, m03 to 50633)
        nodeM01 = TestTalkbackNode(context, m01, 50631, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50632, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50633, hub, peers, autoAcceptIncoming = true)
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
    fun offererEdgeReady_issuesSdpGroupInviteAndM03AcceptsReconnect() {
        val channelId = "P180-C-OFFER"
        val sessionId = "grp:$channelId"
        prepareOffererSession(nodeM01, channelId, sessionId, meshCompletedPeerIds = setOf("M02"))
        prepareAnswererSession(nodeM03, channelId, sessionId)

        assertEquals("ISSUE_INVITE", nodeM01.runtime.testPreviewPairwiseMeshAdmissionActivation(sessionId, "M03"))

        val m01LogMark = synchronized(nodeM01.logs) { nodeM01.logs.size }
        nodeM01.runtime.testNotifyPeerEdgeSignalingReady("M03")

        assertTrue(
            nodeM01.waitForLogSince(m01LogMark, timeoutMs = 5_000L) {
                it.contains("PAIRWISE_MESH_ADMISSION_ACTIVATION_EVALUATED") &&
                    it.contains("decision=ISSUE_INVITE")
            }
        )
        assertTrue(
            nodeM01.waitForLogSince(m01LogMark, timeoutMs = 5_000L) {
                it.contains("PAIRWISE_MESH_ADMISSION_INVITE_ISSUED") && it.contains("peer=M03")
            }
        )
        assertTrue(
            nodeM01.waitForLogSince(m01LogMark, timeoutMs = 5_000L) {
                it.contains("Group invite sent ->") && it.contains("M03")
            }
        )
        assertNoLogEventsSince(
            nodeM01,
            m01LogMark,
            "Group mesh join offered",
            "Group reconnect join offered"
        )
        assertTrue(
            nodeM03.waitForLog(timeoutMs = 8_000L) {
                it.contains("invite reconnect accepted from M01") ||
                    it.contains("invite accepted")
            }
        )
        val obligation = nodeM01.runtime.testPairwiseMeshObligationForPeer(sessionId, "M03")
        assertTrue(obligation?.signalingSatisfied == true)
    }

    @Test
    fun answererDoesNotIssueOfferWhenObligationUnsatisfied() {
        val channelId = "P180-C-ANS"
        val sessionId = "grp:$channelId"
        prepareAnswererSession(nodeM03, channelId, sessionId)

        assertEquals("DEFERRED:ANSWERER_AWAITING_OFFER", nodeM03.runtime.testPreviewPairwiseMeshAdmissionActivation(sessionId, "M01"))

        val m03LogMark = synchronized(nodeM03.logs) { nodeM03.logs.size }
        nodeM03.runtime.testNotifyPeerEdgeSignalingReady("M01")

        assertTrue(
            nodeM03.waitForLogSince(m03LogMark, timeoutMs = 3_000L) {
                it.contains("PAIRWISE_MESH_ADMISSION_ACTIVATION_DEFERRED") &&
                    it.contains("reason=ANSWERER_AWAITING_OFFER")
            }
        )
        assertFalse(nodeM03.hasLogSince(m03LogMark) { it.contains("Group invite sent ->") })
        assertNoLogEventsSince(nodeM03, m03LogMark, "Group mesh join offered")
    }

    @Test
    fun satisfiedObligation_doesNotIssueDuplicateInvite() {
        val channelId = "P180-C-SAT"
        val sessionId = "grp:$channelId"
        prepareOffererSession(
            nodeM01,
            channelId,
            sessionId,
            meshCompletedPeerIds = setOf("M02", "M03")
        )

        assertEquals("NO_ACTION", nodeM01.runtime.testPreviewPairwiseMeshAdmissionActivation(sessionId, "M03"))

        val m01LogMark = synchronized(nodeM01.logs) { nodeM01.logs.size }
        nodeM01.runtime.testNotifyPeerEdgeSignalingReady("M03")
        nodeM01.runtime.reconcileGroupMeshSync(channelId)

        assertFalse(nodeM01.hasLogSince(m01LogMark) { it.contains("PAIRWISE_MESH_ADMISSION_INVITE_ISSUED") })
        assertFalse(nodeM01.hasLogSince(m01LogMark) { it.contains("Group invite sent ->") && it.contains("M03") })
    }

    private fun prepareOffererSession(
        node: TestTalkbackNode,
        channelId: String,
        sessionId: String,
        meshCompletedPeerIds: Set<String>
    ) {
        node.runtime.configureChannelMembership(channelId, listOf("M01", "M02", "M03"))
        node.runtime.testPrepareGroupSessionForPairwiseMeshAdmission(
            channelId = channelId,
            sessionId = sessionId,
            initiatorModuleId = "M01",
            memberModuleIds = listOf("M01", "M02", "M03"),
            meshCompletedPeerIds = meshCompletedPeerIds,
            rosterEpoch = 1L
        )
        node.runtime.testSeedAuthorityDigestForChannel(channelId)
    }

    private fun prepareAnswererSession(
        node: TestTalkbackNode,
        channelId: String,
        sessionId: String
    ) {
        node.runtime.testPrepareGroupSessionForPairwiseMeshAdmission(
            channelId = channelId,
            sessionId = sessionId,
            initiatorModuleId = "M01",
            memberModuleIds = listOf("M01", "M02", "M03"),
            meshCompletedPeerIds = emptySet(),
            rosterEpoch = 1L
        )
        node.runtime.testSeedAuthorityDigestForChannel(channelId)
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
