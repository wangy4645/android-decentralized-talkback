package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.InviteState
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Finding-2 (#180 F2): blocked mesh invite must not leave remoteSignalingInFlight true
 * when handoff never completed (local HAVE_LOCAL_OFFER may remain).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupBootstrapSignalingInFlightGapTest {

    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val hub = InMemorySignalingHub()

    private lateinit var nodeM01: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50671, m02 to 50672, m03 to 50673)
        nodeM01 = TestTalkbackNode(
            context = context,
            moduleId = m01,
            port = 50671,
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
    fun blockedBootstrapInvite_doesNotDeferPairwiseActivationAsSignalingInFlight() {
        val channelId = "BOOT-SIF-GAP"
        val sessionId = "grp:$channelId"
        nodeM01.runtime.configureChannelMembership(channelId, listOf("M01", "M02", "M03"))
        nodeM01.runtime.testPrepareGroupSessionForPairwiseMeshAdmission(
            channelId = channelId,
            sessionId = sessionId,
            initiatorModuleId = "M01",
            memberModuleIds = listOf("M01", "M02", "M03"),
            meshCompletedPeerIds = setOf("M02"),
            rosterEpoch = 1L
        )
        nodeM01.runtime.testSeedAuthorityDigestForChannel(channelId)

        nodeM01.runtime.testBlockPeerControlSignaling("M03", blocked = true)
        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }
        nodeM01.runtime.testRunPairwiseMeshAdmissionActivation(sessionId, "M03")

        assertTrue(
            nodeM01.waitForLogSince(logMark, timeoutMs = 5_000L) {
                it.contains("PEER_EDGE_CONTROL_BLOCKED") &&
                    it.contains("GROUP_INVITE") &&
                    it.contains("peer=M03")
            }
        )
        assertNotEquals(
            "DEFERRED:SIGNALING_IN_FLIGHT",
            nodeM01.runtime.testPreviewPairwiseMeshAdmissionActivation(sessionId, "M03")
        )
        assertFalse(nodeM01.runtime.testRemoteSignalingInFlight(sessionId, "M03"))
        assertNotEquals(
            InviteState.INVITING.name,
            nodeM01.runtime.testParticipantInviteState(sessionId, "M03")
        )
        val signalingState = nodeM01.runtime.testMeshSignalingState(sessionId, "M03")
        assertTrue(
            "PC may retain local negotiation after blocked handoff",
            signalingState == "HAVE_LOCAL_OFFER" || signalingState == "STABLE"
        )

        nodeM01.runtime.testBlockPeerControlSignaling("M03", blocked = false)
        nodeM01.runtime.testNotifyPeerEdgeSignalingReady("M03")
        assertNotEquals(
            "DEFERRED:SIGNALING_IN_FLIGHT",
            nodeM01.runtime.testPreviewPairwiseMeshAdmissionActivation(sessionId, "M03")
        )
        val retryMark = synchronized(nodeM01.logs) { nodeM01.logs.size }
        nodeM01.runtime.testRunPairwiseMeshAdmissionActivation(sessionId, "M03")

        assertTrue(
            nodeM01.hasLogSince(retryMark) {
                it.contains("PAIRWISE_MESH_ADMISSION_INVITE_ISSUED") && it.contains("peer=M03")
            }
        )
    }

    /**
     * Scope pin for #180 F2: legacy pre-handoff INVITING writers outside the F2 whitelist
     * (meshCallInternal) must stay admission-inert. Only a recorded outbound attempt —
     * never InviteState — may feed remoteSignalingInFlight.
     */
    @Test
    fun legacyInvitingWithoutAttempt_isAdmissionInert() {
        val channelId = "BOOT-SIF-SCOPE"
        val sessionId = "grp:$channelId"
        nodeM01.runtime.configureChannelMembership(channelId, listOf("M01", "M02", "M03"))
        nodeM01.runtime.testPrepareGroupSessionForPairwiseMeshAdmission(
            channelId = channelId,
            sessionId = sessionId,
            initiatorModuleId = "M01",
            memberModuleIds = listOf("M01", "M02", "M03"),
            meshCompletedPeerIds = setOf("M02"),
            rosterEpoch = 1L
        )
        nodeM01.runtime.testSeedAuthorityDigestForChannel(channelId)

        nodeM01.runtime.testForceParticipantInviting(sessionId, "M03")

        assertEquals(
            InviteState.INVITING.name,
            nodeM01.runtime.testParticipantInviteState(sessionId, "M03")
        )
        assertFalse(nodeM01.runtime.testRemoteSignalingInFlight(sessionId, "M03"))
        assertNotEquals(
            "DEFERRED:SIGNALING_IN_FLIGHT",
            nodeM01.runtime.testPreviewPairwiseMeshAdmissionActivation(sessionId, "M03")
        )
    }

    private fun TestTalkbackNode.hasLogSince(mark: Int, predicate: (String) -> Boolean): Boolean =
        synchronized(logs) { logs.drop(mark).any(predicate) }
}
