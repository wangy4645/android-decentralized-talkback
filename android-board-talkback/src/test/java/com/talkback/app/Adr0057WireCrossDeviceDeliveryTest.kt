package com.talkback.app

import com.talkback.core.model.ModuleId
import com.talkback.core.session.gbc.AuthoritativeGenerationFact
import com.talkback.core.session.gbc.GenerationFactCandidate
import com.talkback.core.session.gbc.ObligationState
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Cross-device FACT_* candidate transport on SignalEnvelope.
 * Injectable verifier — NOT production crypto evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Adr0057WireCrossDeviceDeliveryTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val hub = InMemorySignalingHub()
    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM02: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 52001, m02 to 52002)
        nodeM01 = TestTalkbackNode(context, m01, 52001, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 52002, hub, peers)
        nodeM01.start()
        nodeM02.start()
        Thread.sleep(300L)
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
    }

    @Test
    fun crossDevice_factRequest_candidate_reachesRemoteVerificationBoundary() {
        val channelId = "CH-WIRE"
        val candidate =
            GenerationFactCandidate(
                claimedFactIdentity = "digest-gstar-wire",
                claimedGenerationIdentity = "G*",
                claimedPredecessorGenerationIdentity = null,
                claimedOriginAuthorityIdentity = "M01",
                claimedAttestsCurrent = true,
                claimedSemanticDigest = "digest-gstar-wire",
                opaqueMaterial = "vm-cross",
            )
        nodeM01.runtime.testGbcStubVerifierSuccess(candidate)
        nodeM01.runtime.testGbcOnCandidateResponse(channelId, "seed", candidate, true)
        assertEquals(ObligationState.CLOSED, nodeM01.runtime.testGbcSnapshot(channelId).obligation)
        assertEquals(
            "G*",
            nodeM01.runtime.testGbcSnapshot(channelId).acceptedCurrent!!.generationIdentity,
        )

        nodeM02.runtime.testGbcStubVerifierSuccess(candidate)
        nodeM02.runtime.testGbcOnHelloLocate(channelId, "M01")
        nodeM02.runtime.testSendGenerationFactRequests(channelId, "corr-wire-1")

        assertTrue(
            nodeM02.waitForLog(timeoutMs = 5_000L) {
                it.contains("FACT_REQUEST sent") && it.contains(channelId)
            },
        )
        assertTrue(
            nodeM01.waitForLog(timeoutMs = 5_000L) {
                it.contains("FACT_REQUEST received") && it.contains(channelId)
            },
        )
        assertTrue(
            nodeM01.waitForLog(timeoutMs = 5_000L) {
                it.contains("FACT_RESPONSE_CANDIDATE sent")
            },
        )
        assertTrue(
            nodeM02.waitForLog(timeoutMs = 5_000L) {
                it.contains("FACT_RESPONSE_CANDIDATE verified") ||
                    it.contains("FACT_RESPONSE_CANDIDATE not promoted")
            },
        )
        assertNotNull(nodeM02.runtime.testGbcSnapshot(channelId).acceptedCurrent)
        assertEquals(
            "G*",
            nodeM02.runtime.testGbcSnapshot(channelId).acceptedCurrent!!.generationIdentity,
        )
    }
}
