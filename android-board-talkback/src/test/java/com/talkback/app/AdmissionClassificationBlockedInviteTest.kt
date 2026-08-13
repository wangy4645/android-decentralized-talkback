package com.talkback.app

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

/** #181-C — blocked GROUP_INVITE observation routes through admission classifier. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AdmissionClassificationBlockedInviteTest {

    private val m01 = com.talkback.core.model.ModuleId("M01")
    private val m02 = com.talkback.core.model.ModuleId("M02")
    private val m03 = com.talkback.core.model.ModuleId("M03")
    private val hub = InMemorySignalingHub()
    private lateinit var nodeM01: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50651, m02 to 50652, m03 to 50653)
        nodeM01 = TestTalkbackNode(
            context = context,
            moduleId = m01,
            port = 50651,
            hub = hub,
            allPeers = peers,
            autoAcceptIncoming = false
        )
        nodeM01.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
    }

    @Test
    fun canonicalPeer_blockedInvite_selectsPairwiseMeshWithoutBootstrapIntent() {
        val channelId = "181C-BLOCK-PAIR"
        val sessionId = "grp:$channelId"
        prepareThreeMemberSession(channelId, sessionId, meshCompletedPeerIds = setOf("M02"))
        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        assertEquals(
            "PAIRWISE_MESH:CANONICAL_PEER_UNSATISFIED_EDGE",
            nodeM01.runtime.testObserveBlockedGroupInviteAdmission(sessionId, "M03")
        )

        val obligation = nodeM01.runtime.testPairwiseMeshObligationForPeer(sessionId, "M03")
        assertNotNull(obligation)
        assertFalse(obligation!!.signalingSatisfied)
        assertFalse(
            nodeM01.hasLogSince(logMark) {
                it.contains("GROUP_BOOTSTRAP_INTENT_CREATED") && it.contains("peer=M03")
            }
        )
        assertFalse(
            nodeM01.hasLogSince(logMark) {
                it.contains("GROUP_BOOTSTRAP_INTENT_WAITING") && it.contains("peer=M03")
            }
        )
    }

    @Test
    fun coldBootstrap_blockedInvite_stillRegistersBootstrapIntent() {
        val channelId = "181C-BLOCK-COLD"
        nodeM01.runtime.testBlockPeerControlSignaling("M02", blocked = true)
        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(
                    com.talkback.core.model.EndpointAddress(m02, com.talkback.core.model.EndpointId("E02"))
                ),
                channelId
            )
        )

        assertTrue(
            nodeM01.waitForLogSince(logMark, timeoutMs = 5_000L) {
                it.contains("GROUP_BOOTSTRAP_INTENT_CREATED") && it.contains("peer=M02")
            }
        )
        assertTrue(
            nodeM01.waitForLogSince(logMark, timeoutMs = 5_000L) {
                it.contains("GROUP_BOOTSTRAP_INTENT_WAITING") && it.contains("peer=M02")
            }
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
