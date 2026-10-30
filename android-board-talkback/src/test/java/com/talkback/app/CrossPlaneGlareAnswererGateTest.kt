package com.talkback.app

import com.talkback.core.model.ConferenceJoinIntent
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.GroupSessionPayload
import com.talkback.core.model.MeshSessionMode
import com.talkback.core.model.ModuleId
import com.talkback.core.session.ConferenceAdmissionPhase
import com.talkback.core.signaling.InMemorySignalingHub
import com.talkback.core.webrtc.StubWebRtcAudioEngine
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CrossPlaneGlareAnswererGateTest {
    private val m01 = ModuleId("M01")
    private val m03 = ModuleId("M03")
    private val hub = InMemorySignalingHub()
    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM03: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50041, m03 to 50043)
        nodeM01 = TestTalkbackNode(context, m01, 50041, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50043, hub, peers)
        nodeM01.start()
        nodeM03.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM03.stop()
        TestTalkbackNode.resetSharedOperationalNetworkRegistryForTest()
    }

    @Test
    fun r1_crossPlaneImpoliteGlare_blocksFalseAcceptAndRetainsLocalOwner() {
        val channelId = "R1-CROSS-PLANE-GLARE"
        val sessionId = startConference(sessionIdChannel = channelId)
        nodeM01.runtime.testClearMeshCompleted(sessionId, m03.value)
        val engine = requireNotNull(nodeM01.runtime.testEnsureConferenceMeshEngine(sessionId, m03.value))
        engine.configureNegotiationState(
            signalingState = "HAVE_LOCAL_OFFER",
            localDescriptionType = "OFFER",
            rejectImpoliteRemoteOfferWithoutSettling = true
        )
        engine.createOffer(iceRestart = true)
        nodeM01.runtime.testBeginConferenceAdmissionHandoff(sessionId, m03.value)

        deliverRecoveryReattachJoin(sessionId, channelId)

        assertTrue(nodeM01.hasLog { it.contains("CROSS_PLANE_GLARE") && it.contains("path=JOIN_FIRST") })
        assertFalse(
            nodeM01.hasLog {
                it.contains("GROUP_ACCEPT_HANDOFF") &&
                    it.contains("path=JOIN_FIRST") &&
                    it.contains("result=SUCCESS")
            }
        )
        assertFalse(nodeM01.hasLog { it.contains("ANSWERER_TRANSACTION_COMMIT") && it.contains("path=JOIN_FIRST") })
        assertFalse(nodeM01.hasLog { it.contains("phase=FAILED reason=ACCEPT_FAILED") })
        assertFalse(nodeM01.hasLog { it.contains("ANSWERER_COMMIT_PRECONDITION_BREACH") })
        assertEquals(ConferenceAdmissionPhase.NEGOTIATING, nodeM01.runtime.conferenceAdmissionPhase(sessionId, m03.value))
        assertTrue(nodeM01.runtime.testConferenceAdmissionHandoffActive(sessionId, m03.value))
        assertFalse(nodeM01.runtime.testMeshCompleted(sessionId, m03.value))
        assertEquals("HAVE_LOCAL_OFFER", nodeM01.runtime.testMeshSignalingState(sessionId, m03.value))
    }

    @Test
    fun r2_normalJoinFirst_settledAcceptCommitsBeforeMeshCompleted() {
        val channelId = "R2-JOIN-FIRST-OK"
        val sessionId = startConference(sessionIdChannel = channelId)
        nodeM01.runtime.testClearMeshCompleted(sessionId, m03.value)
        val engine = requireNotNull(nodeM01.runtime.testEnsureConferenceMeshEngine(sessionId, m03.value))
        engine.impoliteRemoteOfferRejectsWithoutSettling = false

        deliverNormalJoin(sessionId, channelId)

        assertTrue(
            nodeM01.waitForLog(timeoutMs = 5_000L) {
                it.contains("GROUP_ACCEPT_HANDOFF") &&
                    it.contains("path=JOIN_FIRST") &&
                    it.contains("result=SUCCESS")
            }
        )
        assertTrue(
            nodeM01.hasLog {
                it.contains("ANSWERER_TRANSACTION_COMMIT") &&
                    it.contains("path=JOIN_FIRST") &&
                    it.contains("result=TRUE")
            }
        )
        assertEquals(ConferenceAdmissionPhase.READY, nodeM01.runtime.conferenceAdmissionPhase(sessionId, m03.value))
        assertTrue(nodeM01.runtime.testMeshCompleted(sessionId, m03.value))
        assertFalse(nodeM01.hasLog { it.contains("ANSWERER_COMMIT_PRECONDITION_BREACH") })
    }

    @Test
    fun r5_settledAnswererCommitFailure_stillAcceptFailed() {
        val channelId = "R5-COMMIT-FAIL"
        val sessionId = startConference(sessionIdChannel = channelId)
        nodeM01.runtime.testClearMeshCompleted(sessionId, m03.value)
        val engine = requireNotNull(nodeM01.runtime.testEnsureConferenceMeshEngine(sessionId, m03.value))
        engine.configureNegotiationState(signalingState = "STABLE")
        engine.forceAnswererCommitFailure = true

        deliverNormalJoin(sessionId, channelId)

        assertTrue(
            nodeM01.waitForLog(timeoutMs = 5_000L) {
                it.contains("ANSWERER_TRANSACTION_COMMIT") &&
                    it.contains("path=JOIN_FIRST") &&
                    it.contains("result=FALSE")
            }
        )
        assertTrue(nodeM01.hasLog { it.contains("phase=FAILED reason=ACCEPT_FAILED") })
        assertFalse(nodeM01.hasLog { it.contains("ANSWERER_COMMIT_PRECONDITION_BREACH") })
    }

    @Test
    fun stableImpoliteReject_classifiesNoLocalOwnerInStubEngine() {
        val engine = StubWebRtcAudioEngine()
        engine.configureNegotiationState(
            signalingState = "STABLE",
            rejectImpoliteRemoteOfferWithoutSettling = true
        )

        engine.applyRemoteOffer("v=0", polite = false)

        assertFalse(engine.justSettledAsAnswerer())
        assertEquals("STABLE", engine.negotiationSnapshot().signalingState)
    }

    @Test
    fun r7_inviteAnswererPolite_remoteOfferSettlesWithoutCrossPlaneGlare() {
        val engine = StubWebRtcAudioEngine()
        engine.configureNegotiationState(
            signalingState = "HAVE_LOCAL_OFFER",
            localDescriptionType = "OFFER",
            rejectImpoliteRemoteOfferWithoutSettling = true
        )

        engine.applyRemoteOffer("v=0", polite = true)

        assertTrue(engine.justSettledAsAnswerer())
        assertFalse(engine.negotiationSnapshot().signalingState.equals("HAVE_LOCAL_OFFER", ignoreCase = true))
    }

    private fun startConference(sessionIdChannel: String): String {
        nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m03, EndpointId("E01"))),
            sessionIdChannel
        )
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            val snapshot = nodeM01.runtime.sessionSnapshotForChannel(sessionIdChannel)
            if (snapshot != null) return snapshot.sessionId
            Thread.sleep(50L)
        }
        error("conference session not ready for channel=$sessionIdChannel")
    }

    private fun deliverRecoveryReattachJoin(sessionId: String, channelId: String) {
        nodeM01.runtime.testDeliverGroupJoin(
            sessionId = sessionId,
            fromModuleId = m03.value,
            payload = joinPayload(channelId, ConferenceJoinIntent.RECOVERY_REATTACH),
            fromPeer = TestTalkbackNode.peerTarget(50043)
        )
    }

    private fun deliverNormalJoin(sessionId: String, channelId: String) {
        nodeM01.runtime.testDeliverGroupJoin(
            sessionId = sessionId,
            fromModuleId = m03.value,
            payload = joinPayload(channelId, ConferenceJoinIntent.NORMAL_JOIN),
            fromPeer = TestTalkbackNode.peerTarget(50043)
        )
    }

    private fun joinPayload(channelId: String, joinIntent: ConferenceJoinIntent): GroupSessionPayload =
        GroupSessionPayload(
            sdp = "v=0",
            channelId = channelId,
            members = listOf(m01.value, m03.value).sorted(),
            initiatorModuleId = m01.value,
            floorAuthorityModuleId = m01.value,
            sessionMode = MeshSessionMode.CONFERENCE,
            joinIntent = joinIntent,
            offerLineageId = "test-offer-lineage",
            realizationOfferLineageId = "test-realization-lineage",
            realizationAttemptId = "test-realization-attempt"
        )
}

private fun TalkbackRuntime.requireConferenceCall(
    from: EndpointAddress,
    remoteEndpoints: List<EndpointAddress>,
    channelId: String,
): String = requireNotNull(conferenceCall(from, remoteEndpoints, channelId)) {
    "conferenceCall blocked"
}
