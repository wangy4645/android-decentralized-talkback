package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.HelloPayload
import com.talkback.core.model.ModuleId
import com.talkback.core.model.RemoteEndpointInfo
import com.talkback.core.security.SignalSecurity
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.signaling.InMemorySignalingHub
import com.talkback.core.signaling.PeerTarget
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
import java.util.UUID

/** Late-peer admission: HELLO → tryReinviteGroupPeerPairwise (scale-neutral seam). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupLatePeerAdmissionIntegrationTest {

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
        nodeM01 = TestTalkbackNode(context, m01, 50641, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50642, hub, peers, autoAcceptIncoming = true)
        nodeM01.start()
        nodeM02.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
    }

    @Test
    fun coldHelloFromLatePeer_triggersDiscoverAndInviteWithoutReconcileStorm() {
        val channelId = "LATE-PEER-01"
        val sessionId = prepareOperationalGroup(channelId)
        nodeM01.runtime.testResetLatePeerAdmissionMetrics()
        val reconcileBefore = nodeM01.runtime.testReconcileGroupMeshInternalInvocationCount()
        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        deliverColdHello(fromModuleId = "M03", channelId = channelId, port = 50643)

        assertTrue(
            nodeM01.waitForLogSince(logMark, timeoutMs = 8_000L) {
                it.contains("GROUP_LATE_PEER_DISCOVERED") &&
                    it.contains("peer=M03") &&
                    it.contains("channelSource=HELLO")
            }
        )
        assertTrue(
            nodeM01.waitForLogSince(logMark, timeoutMs = 8_000L) {
                it.contains("Group invite sent ->") && it.contains("M03")
            }
        )
        assertEquals(reconcileBefore, nodeM01.runtime.testReconcileGroupMeshInternalInvocationCount())
        assertTrue(nodeM01.runtime.testLatePeerAdmitInvokeCount() >= 1)
        assertFalse(nodeM01.runtime.testIsCanonicalGroupMember(sessionId, "M03"))
    }

    @Test
    fun buildingAuthority_helloFromM03_discoversAndInvites() {
        val channelId = "LATE-PEER-BUILDING"
        prepareBuildingGroup(channelId)
        nodeM01.runtime.testResetLatePeerAdmissionMetrics()
        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        deliverColdHello(fromModuleId = "M03", channelId = channelId, port = 50643)

        assertTrue(
            nodeM01.waitForLogSince(logMark, timeoutMs = 8_000L) {
                it.contains("GROUP_LATE_PEER_DISCOVERED") && it.contains("peer=M03")
            }
        )
        assertTrue(
            nodeM01.waitForLogSince(logMark, timeoutMs = 8_000L) {
                it.contains("Group invite sent ->") && it.contains("M03")
            }
        )
        assertFalse(
            nodeM01.hasLogSince(logMark) {
                it.contains("GROUP_LATE_PEER_ADMISSION_SKIPPED") && it.contains("peer=M03")
            }
        )
        assertTrue(nodeM01.runtime.testLatePeerAdmitInvokeCount() >= 1)
    }

    @Test
    fun nonAuthorityNode_skipsLatePeerAdmission() {
        val channelId = "LATE-PEER-02"
        prepareOperationalGroup(channelId)
        val logMark = synchronized(nodeM02.logs) { nodeM02.logs.size }

        // M02 is not lex-offerer for M00 (M02 > M00) and is not membership authority.
        deliverColdHelloTo(
            node = nodeM02,
            fromModuleId = "M00",
            channelId = channelId,
            port = 50640
        )

        assertTrue(
            nodeM02.waitForLogSince(logMark, timeoutMs = 5_000L) {
                it.contains("GROUP_LATE_PEER_ADMISSION_SKIPPED") &&
                    it.contains("peer=M00") &&
                    it.contains("NOT_AUTHORITY_OR_OFFERER")
            }
        )
        assertFalse(
            nodeM02.hasLogSince(logMark) {
                it.contains("Group invite sent ->") && it.contains("M00")
            }
        )
    }

    @Test
    fun helloStorm_doesNotAmplifyInvitesOrReconcile() {
        val channelId = "LATE-PEER-STORM"
        prepareOperationalGroup(channelId)
        nodeM01.runtime.testResetLatePeerAdmissionMetrics()
        val reconcileBefore = nodeM01.runtime.testReconcileGroupMeshInternalInvocationCount()
        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        repeat(20) {
            deliverColdHello(fromModuleId = "M04", channelId = channelId, port = 50644)
        }

        val inviteLines = synchronized(nodeM01.logs) {
            nodeM01.logs.drop(logMark).count {
                it.contains("Group invite sent ->") && it.contains("M04")
            }
        }
        assertTrue("expected bounded invites, got $inviteLines", inviteLines <= 2)
        assertEquals(reconcileBefore, nodeM01.runtime.testReconcileGroupMeshInternalInvocationCount())
    }

    @Test
    fun channelLessHello_singleAcceptedSession_discoversAndInvites() {
        val channelId = "LATE-PEER-NOCH"
        prepareBuildingGroup(channelId)
        nodeM01.runtime.testResetLatePeerAdmissionMetrics()
        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        deliverColdHello(fromModuleId = "M04", channelId = null, port = 50644)

        assertTrue(
            nodeM01.waitForLogSince(logMark, timeoutMs = 8_000L) {
                it.contains("GROUP_LATE_PEER_DISCOVERED") &&
                    it.contains("peer=M04") &&
                    it.contains("channelSource=LOCAL_ACCEPTED_SESSION")
            }
        )
        assertTrue(
            nodeM01.waitForLogSince(logMark, timeoutMs = 8_000L) {
                it.contains("Group invite sent ->") && it.contains("M04")
            }
        )
        assertTrue(nodeM01.runtime.testLatePeerAdmitInvokeCount() >= 1)
    }

    @Test
    fun channelLessHello_multipleAcceptedSessions_skipped() {
        nodeM01.runtime.testSeedDuplicateGroupSession(
            channelId = "LATE-PEER-MULTI-A",
            sessionId = "dup-session-a",
            initiatorModuleId = "M01",
            connectedPeerModuleIds = listOf("M02")
        )
        nodeM01.runtime.testSeedDuplicateGroupSession(
            channelId = "LATE-PEER-MULTI-B",
            sessionId = "dup-session-b",
            initiatorModuleId = "M01",
            connectedPeerModuleIds = listOf("M02")
        )
        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        deliverColdHello(fromModuleId = "M04", channelId = null, port = 50644)

        assertTrue(
            nodeM01.waitForLogSince(logMark, timeoutMs = 8_000L) {
                it.contains("GROUP_LATE_PEER_ADMISSION_SKIPPED") &&
                    it.contains("peer=M04") &&
                    it.contains("HELLO_CHANNEL_ABSENT")
            }
        )
        assertFalse(
            nodeM01.hasLogSince(logMark) {
                it.contains("GROUP_LATE_PEER_DISCOVERED") && it.contains("peer=M04")
            }
        )
    }

    @Test
    fun channelLessHello_storm_doesNotAmplifyInvites() {
        val channelId = "LATE-PEER-NOCH-STORM"
        prepareBuildingGroup(channelId)
        nodeM01.runtime.testResetLatePeerAdmissionMetrics()
        val reconcileBefore = nodeM01.runtime.testReconcileGroupMeshInternalInvocationCount()
        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        repeat(20) {
            deliverColdHello(fromModuleId = "M04", channelId = null, port = 50644)
        }

        val inviteLines = synchronized(nodeM01.logs) {
            nodeM01.logs.drop(logMark).count {
                it.contains("Group invite sent ->") && it.contains("M04")
            }
        }
        assertTrue("expected bounded invites, got $inviteLines", inviteLines <= 2)
        assertEquals(reconcileBefore, nodeM01.runtime.testReconcileGroupMeshInternalInvocationCount())
    }

    @Test
    fun multiLatePeers_eachGetsAdmissionAttemptWithoutReconcile() {
        val channelId = "LATE-PEER-MULTI"
        prepareOperationalGroup(channelId)
        nodeM01.runtime.testResetLatePeerAdmissionMetrics()
        val reconcileBefore = nodeM01.runtime.testReconcileGroupMeshInternalInvocationCount()
        val logMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        listOf("M04" to 50644, "M05" to 50645, "M06" to 50646, "M07" to 50647, "M08" to 50648)
            .forEach { (peer, port) ->
                deliverColdHello(fromModuleId = peer, channelId = channelId, port = port)
            }

        assertTrue(
            nodeM01.waitForLogSince(logMark, timeoutMs = 8_000L) {
                synchronized(nodeM01.logs) {
                    nodeM01.logs.drop(logMark).count { it.contains("GROUP_LATE_PEER_DISCOVERED") }
                } >= 5
            }
        )
        val discovered = synchronized(nodeM01.logs) {
            nodeM01.logs.drop(logMark).count { it.contains("GROUP_LATE_PEER_DISCOVERED") }
        }
        assertEquals(5, discovered)
        assertEquals(reconcileBefore, nodeM01.runtime.testReconcileGroupMeshInternalInvocationCount())
        assertTrue(nodeM01.runtime.testLatePeerAdmitInvokeCount() >= 5)
    }

    private fun prepareOperationalGroup(channelId: String): String {
        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(EndpointAddress(m02, EndpointId("E01"))),
                channelId
            )
        )
        assertTrue(nodeM02.waitForLog(timeoutMs = 8_000L) { it.contains("invite accepted") })
        nodeM01.runtime.simulateRemoteIceState("M02", "CONNECTED")
        nodeM02.runtime.simulateRemoteIceState("M01", "CONNECTED")
        nodeM01.runtime.testSeedAuthorityDigestForChannel(channelId)
        nodeM02.runtime.testSeedAuthorityDigestForChannel(channelId)
        assertTrue(
            nodeM01.waitForLog(timeoutMs = 8_000L) {
                it.contains("groupTopologyReadiness=OPERATIONAL")
            }
        )
        return sessionId
    }

    private fun prepareBuildingGroup(channelId: String): String {
        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(EndpointAddress(m02, EndpointId("E01"))),
                channelId
            )
        )
        assertTrue(nodeM02.waitForLog(timeoutMs = 8_000L) { it.contains("invite accepted") })
        nodeM01.runtime.testSeedAuthorityDigestForChannel(channelId)
        assertTrue(
            "expected BUILDING (ICE not connected)",
            nodeM01.waitForLog(timeoutMs = 8_000L) {
                it.contains("groupTopologyReadiness=BUILDING")
            }
        )
        return sessionId
    }

    private fun deliverColdHello(fromModuleId: String, channelId: String?, port: Int) {
        deliverColdHelloTo(nodeM01, fromModuleId, channelId, port)
    }

    private fun deliverColdHelloTo(
        node: TestTalkbackNode,
        fromModuleId: String,
        channelId: String?,
        port: Int
    ) {
        val payload = HelloPayload(
            moduleId = fromModuleId,
            endpoints = listOf(
                RemoteEndpointInfo(
                    endpointId = "E01",
                    displayName = "LatePeer",
                    online = true
                )
            ),
            channelId = channelId,
            rosterEpoch = 0L,
            memberHash = 0
        )
        val envelope = signedHello(fromModuleId, payload.encode())
        node.runtime.testDeliverHello(envelope, PeerTarget(TestTalkbackNode.TEST_HOST, port))
    }

    private fun signedHello(moduleId: String, payload: String): SignalEnvelope {
        val from = EndpointAddress(ModuleId(moduleId), EndpointId("E01"))
        val unsigned = SignalEnvelope(
            type = SignalType.HELLO,
            from = from,
            to = null,
            sessionId = "hello",
            timestampMs = System.currentTimeMillis(),
            payload = payload,
            nonce = UUID.randomUUID().toString(),
            signature = ""
        )
        return unsigned.copy(signature = SignalSecurity.sign(unsigned, "test-secret"))
    }

    private fun TestTalkbackNode.hasLogSince(mark: Int, predicate: (String) -> Boolean): Boolean =
        synchronized(logs) { logs.drop(mark).any(predicate) }
}
