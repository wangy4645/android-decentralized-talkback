package com.talkback.app

import com.talkback.core.model.ModuleId
import com.talkback.core.session.gbc.AuthoritativeGenerationFact
import com.talkback.core.session.gbc.ConvergenceEffect
import com.talkback.core.session.gbc.LocalGenerationKnowledge
import com.talkback.core.session.gbc.ObligationState
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ADR-0057 Integration Conformance — Coordinator hosts GBC wiring;
 * generation truth remains in GBC (post-verification Fact seam).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Adr0057GbcCoordinatorIntegrationTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val hub = InMemorySignalingHub()
    private lateinit var node: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 51001, m02 to 51002)
        node = TestTalkbackNode(context, m01, 51001, hub, peers)
        node.start()
    }

    @After
    fun tearDown() {
        node.stop()
    }

    @Test
    fun coordinator_wiresFactThroughGbc_fenceAndRealignEffects_noInventedTruth() {
        val channelId = "CH-GBC-INT"
        node.runtime.testGbcObserveLocalGeneration(channelId, "G1", treatedAsCurrent = true)
        assertEquals(ObligationState.OPEN, node.runtime.testGbcSnapshot(channelId).obligation)

        val g2 =
            AuthoritativeGenerationFact(
                generationIdentity = "G2",
                predecessorGenerationIdentity = "G1",
                originAuthorityIdentity = "M01",
                attestsCurrent = true,
                semanticDigest = "digest-g2-int",
            )
        val effects = node.runtime.testGbcAcceptVerifiedFact(channelId, g2)

        val snap = node.runtime.testGbcSnapshot(channelId)
        assertEquals("G2", snap.acceptedCurrent!!.generationIdentity)
        assertEquals(ObligationState.CLOSED, snap.obligation)
        assertTrue(snap.knowledge is LocalGenerationKnowledge.Known)
        assertEquals(
            "G2",
            (snap.knowledge as LocalGenerationKnowledge.Known).generationIdentity,
        )
        assertTrue(effects.any { it is ConvergenceEffect.FenceGeneration && it.generationIdentity == "G1" })
        assertTrue(effects.any { it is ConvergenceEffect.RealignToCurrent })
        assertTrue(
            node.waitForLog(timeoutMs = 3_000L) {
                it.contains("GBC_EFFECT FenceGeneration") && it.contains("generation=G1")
            },
        )
        assertTrue(
            node.waitForLog(timeoutMs = 3_000L) {
                it.contains("GBC_EFFECT RealignToCurrent") && it.contains("current=G2")
            },
        )
    }
}
