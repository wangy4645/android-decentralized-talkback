package com.talkback.app

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

/** #180-B — snapshot seam must not swallow unsatisfied pairwise mesh obligations. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupPairwiseMeshSnapshotAdmissionTest {

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
        val peers = TestTalkbackNode.allPeers(m01 to 50601, m02 to 50602, m03 to 50603)
        nodeM01 = TestTalkbackNode(context, m01, 50601, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50602, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50603, hub, peers)
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
    fun snapshotWithSatisfiedEdge_doesNotDeferMeshCompletion() {
        val channelId = "P180-B-SAT"
        val sessionId = "grp:$channelId"
        prepareM03Session(channelId, sessionId, meshCompletedPeerIds = setOf("M01"))

        val before = nodeM03.runtime.testPairwiseMeshObligationForPeer(sessionId, "M01")
        assertNotNull(before)
        assertTrue(before!!.signalingSatisfied)

        val logMark = synchronized(nodeM03.logs) { nodeM03.logs.size }
        injectSnapshotFromM01(channelId, sessionId, rosterEpoch = 2L)

        val after = nodeM03.runtime.testPairwiseMeshObligationForPeer(sessionId, "M01")
        assertNotNull(after)
        assertTrue(after!!.signalingSatisfied)
        assertFalse(
            nodeM03.hasLogSince(logMark) {
                it.contains("PAIRWISE_MESH_ADMISSION_DEFERRED") && it.contains("peer=M01")
            }
        )
    }

    @Test
    fun snapshotWithMissingEdge_retainsUnsatisfiedObligation() {
        val channelId = "P180-B-MISS"
        val sessionId = "grp:$channelId"
        prepareM03Session(channelId, sessionId, meshCompletedPeerIds = emptySet())

        injectSnapshotFromM01(channelId, sessionId, rosterEpoch = 2L)

        val obligation = nodeM03.runtime.testPairwiseMeshObligationForPeer(sessionId, "M01")
        assertNotNull(obligation)
        assertFalse(obligation!!.signalingSatisfied)
        assertEquals("M01|M03", obligation.edgeKey)
    }

    @Test
    fun snapshotWithM01M03Missing_preservesPerfectNegotiationRoles() {
        val channelId = "P180-B-ROLES"
        val sessionId = "grp:$channelId"
        prepareM03Session(channelId, sessionId, meshCompletedPeerIds = emptySet())

        injectSnapshotFromM01(channelId, sessionId, rosterEpoch = 2L)

        val obligation = nodeM03.runtime.testPairwiseMeshObligationForPeer(sessionId, "M01")
        assertNotNull(obligation)
        assertEquals("M01", obligation!!.offerer)
        assertEquals("M03", obligation.answerer)
    }

    @Test
    fun snapshotWithMissingEdge_neverEmitsGroupJoin() {
        val channelId = "P180-B-NOJOIN"
        val sessionId = "grp:$channelId"
        prepareM03Session(channelId, sessionId, meshCompletedPeerIds = emptySet())

        val logMark = synchronized(nodeM03.logs) { nodeM03.logs.size }
        injectSnapshotFromM01(channelId, sessionId, rosterEpoch = 2L)

        assertTrue(
            nodeM03.waitForLogSince(logMark, timeoutMs = 2_000L) {
                it.contains("PAIRWISE_MESH_ADMISSION_DEFERRED") && it.contains("peer=M01")
            }
        )
        assertNoLogEventsSince(
            nodeM03,
            logMark,
            "Group mesh join offered",
            "Group reconnect join offered",
            "joinIngressDecision=QUEUED_NO_SESSION"
        )
    }

    private fun prepareM03Session(
        channelId: String,
        sessionId: String,
        meshCompletedPeerIds: Set<String>
    ) {
        nodeM03.runtime.testPrepareGroupSessionForPairwiseMeshAdmission(
            channelId = channelId,
            sessionId = sessionId,
            initiatorModuleId = "M01",
            memberModuleIds = listOf("M01", "M02", "M03"),
            meshCompletedPeerIds = meshCompletedPeerIds,
            rosterEpoch = 1L
        )
        nodeM03.runtime.testSeedAuthorityDigestForChannel(channelId)
    }

    private fun injectSnapshotFromM01(channelId: String, sessionId: String, rosterEpoch: Long) {
        val sent = nodeM03.runtime.testInjectMembershipSnapshotInvite(
            callerModuleId = "M01",
            channelId = channelId,
            sessionId = sessionId,
            fromPeer = TestTalkbackNode.peerTarget(50601),
            memberModuleIds = listOf("M01", "M02", "M03"),
            rosterEpoch = rosterEpoch
        )
        assertTrue(sent)
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
