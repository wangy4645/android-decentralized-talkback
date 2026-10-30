package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * OPS-07 regression: M02 accept must not purge M03 outstanding CR2; M03 accept must apply answer,
 * not silently create CR3 via getOrCreate on the accept path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Ops07ConferenceSequentialAcceptIntegrationTest {
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
        val peers = TestTalkbackNode.allPeers(
            m01 to 50021,
            m02 to 50022,
            m03 to 50023,
        )
        nodeM01 = TestTalkbackNode(context, m01, 50021, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50022, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50023, hub, peers)
        nodeM01.start()
        nodeM02.start()
        nodeM03.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        nodeM03.stop()
        TestTalkbackNode.resetSharedOperationalNetworkRegistryForTest()
    }

    @Test
    fun m02AcceptFirst_doesNotPurgeM03OutstandingOffer_m03AcceptAppliesWithoutCr3() {
        val channelId = "OPS07-SEQ-ACCEPT"
        nodeM03.runtime.setAutoAcceptConferenceInvites(false)

        val sessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01")),
            ),
            channelId,
        )

        assertTrue(
            nodeM02.waitForLog(timeoutMs = 10_000L) {
                it.contains("Conference invite accepted") || it.contains("invite accepted")
            }
        )
        assertTrue(
            nodeM01.waitForLog(timeoutMs = 10_000L) {
                it.contains("APPLY_REMOTE_ANSWER") && it.contains("remote=M02")
            }
        )

        assertFalse(
            nodeM01.hasLog {
                it.contains("CONFERENCE_RESIDUAL_PC_PURGE") && it.contains("peer=M03")
            }
        )

        val m03GenerationBeforeAccept = nodeM01.runtime.conferenceMediaGeneration("M03")
        assertNotNull(m03GenerationBeforeAccept)

        assertNotNull(nodeM03.runtime.pendingConferenceInvite(channelId))
        assertTrue(nodeM03.runtime.acceptPendingConferenceInvite(channelId))

        assertTrue(
            nodeM01.waitForLog(timeoutMs = 15_000L) {
                it.contains("APPLY_REMOTE_ANSWER") &&
                    it.contains("remote=M03") &&
                    it.contains("lineageCorrelation=MATCH")
            }
        )
        assertFalse(
            nodeM01.hasLog {
                it.contains("CREATE_OFFER") &&
                    it.contains("remote=M03") &&
                    it.contains("offerLineageId=CR3")
            }
        )
        assertFalse(
            nodeM01.hasLog {
                it.contains("Synchronous mesh engine create pending async release for M03")
            }
        )
        assertFalse(
            nodeM01.hasLog {
                it.contains("CONFERENCE_ACCEPT_FAIL_CLOSED") && it.contains("peer=M03")
            }
        )
        val m03GenerationAfterAccept = nodeM01.runtime.conferenceMediaGeneration("M03")
        assertNotNull(m03GenerationAfterAccept)
        assertTrue(
            "M03 pcGen must not bump on correlated accept apply",
            m03GenerationAfterAccept == m03GenerationBeforeAccept
        )
        assertTrue(nodeM01.runtime.activeSessionIds().contains(sessionId))
    }
}

private fun TalkbackRuntime.requireConferenceCall(
    from: EndpointAddress,
    remoteEndpoints: List<EndpointAddress>,
    channelId: String,
): String = requireNotNull(conferenceCall(from, remoteEndpoints, channelId)) {
    "conferenceCall blocked"
}
