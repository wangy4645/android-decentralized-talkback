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
 * R2-P1 PEER-SOURCE-PUBLICATION — participant accepted rising edge materializes topology
 * authority, then SOURCE fanout is allowed to enter the existing snapshot fence.
 *
 * Field ordering reproduced:
 * ```
 * invite_accept Mesh while accepted=false → topology skipped
 * accepted=true → CONFERENCE_TOPOLOGY_PUBLISHED
 * → publishSourceDeclarationToEligiblePeers enters onEligiblePeers
 * ```
 *
 * Stub dual-node has no GBC Profile01 signer / media material, so factType=9 may land in
 * `ORIGIN_SOURCE_GAP` (`NO_SIGNER` / `NO_BUILT_SOURCE`). That still proves the R2-P1 edge:
 * fanout is no longer silently lost on `currentSnapshot == null`. Full BUILT→EMITTED→WIRE_INGRESS
 * remains covered by Profile01 origin bridge harnesses once snapshot + material exist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PeerSourcePublicationAcceptedTopologyTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val hub = InMemorySignalingHub()
    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM02: TestTalkbackNode

    @Before
    fun setUp() {
        TestTalkbackNode.resetSharedOperationalNetworkRegistryForTest()
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50111, m02 to 50112)
        nodeM01 = TestTalkbackNode(context, m01, 50111, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50112, hub, peers, autoAcceptIncoming = false)
        nodeM01.start()
        nodeM02.start()
    }

    @After
    fun tearDown() {
        if (this::nodeM01.isInitialized) nodeM01.stop()
        if (this::nodeM02.isInitialized) nodeM02.stop()
        TestTalkbackNode.resetSharedOperationalNetworkRegistryForTest()
    }

    @Test
    fun participantAccept_materializesTopologyThenDrivesSourceFanoutAttempt() {
        val channelId = "CONF-PEER-SRC-PUB"
        nodeM02.runtime.setAutoAcceptConferenceInvites(false)

        nodeM01.runtime.conferenceCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m02, EndpointId("E01"))),
            channelId,
        )
        val pendingDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < pendingDeadline) {
            if (nodeM02.runtime.pendingConferenceInvite(channelId) != null) break
            Thread.sleep(50L)
        }
        assertNotNull(nodeM02.runtime.pendingConferenceInvite(channelId))

        assertFalse(
            "Mesh admission while accepted=false must not publish topology",
            nodeM02.hasLog { it.contains("CONFERENCE_TOPOLOGY_PUBLISHED") },
        )

        val mark = nodeM02.logs.size
        assertTrue(nodeM02.runtime.acceptPendingConferenceInvite(channelId))

        assertTrue(
            "invite_accept Mesh admission while accepted=false must skip (field fingerprint)",
            nodeM02.waitForLogSince(mark, timeoutMs = 10_000L) {
                it.contains("CONFERENCE_ADMISSION_MESH") && it.contains("reason=invite_accept")
            },
        )
        assertTrue(
            nodeM02.waitForLogSince(mark, timeoutMs = 10_000L) {
                it.contains("CONFERENCE_TOPOLOGY_PUBLISHED") && it.contains("reason=participant_accepted")
            },
        )

        assertTrue(
            "SOURCE fanout must enter after snapshot materialization (not silent null-snapshot return)",
            nodeM02.waitForLogSince(mark, timeoutMs = 10_000L) { line ->
                line.contains("PROFILE01_ORIGIN_EMIT") && line.contains("factType=9")
            },
        )

        val sinceAccept =
            synchronized(nodeM02.logs) { nodeM02.logs.drop(mark) }
        val topologyIdx =
            sinceAccept.indexOfFirst {
                it.contains("CONFERENCE_TOPOLOGY_PUBLISHED") && it.contains("reason=participant_accepted")
            }
        val sourceEmitIdx =
            sinceAccept.indexOfFirst {
                it.contains("PROFILE01_ORIGIN_EMIT") && it.contains("factType=9")
            }
        assertTrue("topologyIdx=$topologyIdx sourceEmitIdx=$sourceEmitIdx", topologyIdx >= 0 && sourceEmitIdx >= 0)
        assertTrue(
            "topology publication must precede SOURCE fanout attempt",
            topologyIdx < sourceEmitIdx,
        )
    }

    @Test
    fun participantAccept_repeatedAcceptedDrive_doesNotRepublishTopology() {
        val channelId = "CONF-PEER-SRC-IDEM"
        nodeM02.runtime.setAutoAcceptConferenceInvites(false)
        nodeM01.runtime.conferenceCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m02, EndpointId("E01"))),
            channelId,
        )
        val pendingDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < pendingDeadline) {
            if (nodeM02.runtime.pendingConferenceInvite(channelId) != null) break
            Thread.sleep(50L)
        }
        assertTrue(nodeM02.runtime.acceptPendingConferenceInvite(channelId))
        assertTrue(
            nodeM02.waitForLog(timeoutMs = 10_000L) {
                it.contains("CONFERENCE_TOPOLOGY_PUBLISHED") && it.contains("reason=participant_accepted")
            },
        )
        assertTrue(
            nodeM02.waitForLog(timeoutMs = 10_000L) {
                it.contains("PROFILE01_ORIGIN_EMIT") && it.contains("factType=9")
            },
        )

        val firstPublishCount =
            synchronized(nodeM02.logs) {
                nodeM02.logs.count {
                    it.contains("CONFERENCE_TOPOLOGY_PUBLISHED") && it.contains("participant_accepted")
                }
            }
        assertTrue("topology publish count=$firstPublishCount", firstPublishCount >= 1)
        Thread.sleep(400L)
        val laterPublishCount =
            synchronized(nodeM02.logs) {
                nodeM02.logs.count {
                    it.contains("CONFERENCE_TOPOLOGY_PUBLISHED") && it.contains("participant_accepted")
                }
            }
        assertTrue(
            "repeat accepted/topology drive must not keep publishing new topology generations",
            laterPublishCount == firstPublishCount,
        )
    }
}
