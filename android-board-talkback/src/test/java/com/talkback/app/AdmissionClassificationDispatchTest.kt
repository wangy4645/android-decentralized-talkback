package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
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

/** #181-B — P1 newInvitee admission routes through domain classifier before bootstrap registration. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AdmissionClassificationDispatchTest {

    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val hub = InMemorySignalingHub()
    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM02: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50641, m02 to 50642, m03 to 50643)
        nodeM01 = TestTalkbackNode(
            context = context,
            moduleId = m01,
            port = 50641,
            hub = hub,
            allPeers = peers,
            autoAcceptIncoming = false
        )
        nodeM02 = TestTalkbackNode(context, m02, 50642, hub, peers, autoAcceptIncoming = false)
        nodeM01.start()
        nodeM02.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
    }

    @Test
    fun coldBootstrap_groupCall_registersBootstrapIntent() {
        val channelId = "181B-COLD"
        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(EndpointAddress(m02, EndpointId("E02"))),
                channelId
            )
        )
        assertTrue(sessionId.isNotEmpty())
        assertTrue(
            nodeM01.waitForLogSince(logMark, timeoutMs = 5_000L) {
                it.contains("GROUP_BOOTSTRAP_INTENT_CREATED") && it.contains("peer=M02")
            }
        )
    }

    @Test
    fun existingSessionNewPeer_notCanonical_doesNotRegisterBootstrapIntent() {
        val channelId = "181B-NONE"
        val sessionId = "grp:$channelId"
        prepareTwoMemberSession(channelId, sessionId)
        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        nodeM01.runtime.testSendGroupMeshInvites(
            sessionId,
            listOf(EndpointAddress(m03, EndpointId("E03")))
        )

        assertEquals(
            "NONE:NO_ADMISSION_DOMAIN",
            nodeM01.runtime.testClassifyAdmissionForPeer(sessionId, "M03")
        )
        assertFalse(
            nodeM01.hasLogSince(logMark) {
                it.contains("GROUP_BOOTSTRAP_INTENT_CREATED") && it.contains("peer=M03")
            }
        )
    }

    @Test
    fun canonicalPeer_unsatisfiedEdge_dispatchSelectsPairwiseMeshWithoutBootstrap() {
        val channelId = "181B-PAIR"
        val sessionId = "grp:$channelId"
        prepareThreeMemberSession(channelId, sessionId, meshCompletedPeerIds = setOf("M02"))
        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        assertEquals(
            "PAIRWISE_MESH:CANONICAL_PEER_UNSATISFIED_EDGE",
            nodeM01.runtime.testClassifyAdmissionForPeer(sessionId, "M03")
        )
        assertEquals(
            "PAIRWISE_MESH:CANONICAL_PEER_UNSATISFIED_EDGE",
            nodeM01.runtime.testDispatchNewInviteeAdmission(sessionId, "M03")
        )

        val obligation = nodeM01.runtime.testPairwiseMeshObligationForPeer(sessionId, "M03")
        assertNotNull(obligation)
        assertFalse(obligation!!.signalingSatisfied)
        assertEquals("M01|M03", obligation.edgeKey)
        assertFalse(
            nodeM01.hasLogSince(logMark) {
                it.contains("GROUP_BOOTSTRAP_INTENT_CREATED") && it.contains("peer=M03")
            }
        )
    }

    @Test
    fun connectedPeer_selectsNone() {
        val channelId = "181B-CONN"
        val sessionId = "grp:$channelId"
        prepareThreeMemberSession(channelId, sessionId, meshCompletedPeerIds = setOf("M02", "M03"))

        assertEquals(
            "NONE:NO_ADMISSION_DOMAIN",
            nodeM01.runtime.testClassifyAdmissionForPeer(sessionId, "M03")
        )
    }

    private fun prepareTwoMemberSession(channelId: String, sessionId: String) {
        nodeM01.runtime.testPrepareGroupSessionForPairwiseMeshAdmission(
            channelId = channelId,
            sessionId = sessionId,
            initiatorModuleId = "M01",
            memberModuleIds = listOf("M01", "M02"),
            meshCompletedPeerIds = setOf("M02")
        )
    }

    private fun prepareThreeMemberSession(
        channelId: String,
        sessionId: String,
        meshCompletedPeerIds: Set<String>
    ) {
        nodeM01.runtime.testPrepareGroupSessionForPairwiseMeshAdmission(
            channelId = channelId,
            sessionId = sessionId,
            initiatorModuleId = "M01",
            memberModuleIds = listOf("M01", "M02", "M03"),
            meshCompletedPeerIds = meshCompletedPeerIds
        )
    }

    private fun TestTalkbackNode.hasLogSince(mark: Int, predicate: (String) -> Boolean): Boolean =
        synchronized(logs) { logs.drop(mark).any(predicate) }
}
