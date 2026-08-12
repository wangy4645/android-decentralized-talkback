package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
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

/** ADR-0053 E4 — formerly-admitted peer rejoin admission (#178). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupE4RejoinAdmissionTest {

    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val hub = com.talkback.core.signaling.InMemorySignalingHub()
    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM02: TestTalkbackNode
    private lateinit var nodeM03: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50601, m02 to 50602)
        nodeM01 = TestTalkbackNode(context, m01, 50601, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50602, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50603, hub, peers)
        nodeM01.start()
        nodeM02.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        if (::nodeM03.isInitialized) {
            nodeM03.stop()
        }
    }

    @Test
    fun t9a_formerlyAdmittedPeerRejoin_issuesE4InviteAndCommitsAdmission() {
        val channelId = "T9-A-E4"
        val sessionId = bootstrapThreeNodeGroup(channelId)
        nodeM02.runtime.testDropLocalGroupSession(sessionId)
        val m01LogMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        val (hasHistory, stillCanonicalAfterEvict, epochAfterEvict) =
            nodeM01.runtime.testEvictGroupMemberAtomic(sessionId, "M02")
        assertTrue("history missing after evict", hasHistory)
        assertFalse("M02 still canonical after evict", stillCanonicalAfterEvict)

        nodeM02.refreshDiscovery()
        if (!nodeM01.hasLog { it.contains("GROUP_E4_REJOIN_INVITE_ISSUED") }) {
            triggerE4Rejoin(nodeM01, channelId, sessionId)
        }

        assertTrue(
            nodeM01.waitForLogSince(m01LogMark, timeoutMs = 8_000L) {
                it.contains("GROUP_E4_REJOIN_INVITE_ISSUED")
            }
        )
        assertNoLogEventsSince(
            nodeM01,
            m01LogMark,
            "Group reconnect join offered",
            "joinIngressDecision=QUEUED_NO_SESSION"
        )
        assertTrue(nodeM02.waitForLog(timeoutMs = 8_000L) { it.contains("invite accepted") })
        assertTrue(
            nodeM01.waitForLogSince(m01LogMark, timeoutMs = 8_000L) {
                it.contains("GROUP_E4_REJOIN_ADMISSION_COMMITTED")
            }
        )
        assertTrue(nodeM01.runtime.testIsCanonicalGroupMember(sessionId, "M02"))
        assertTrue(nodeM01.runtime.testRosterEpoch(sessionId) > epochAfterEvict)
        assertFalse(nodeM01.runtime.testHasFormerlyAdmittedPeer(sessionId, "M02"))
    }

    @Test
    fun t9b_rosterPreservedWithoutPrune_doesNotIssueE4() {
        val channelId = "T9-B-E4"
        val sessionId = bootstrapThreeNodeGroup(channelId)
        val m01LogMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        nodeM01.runtime.reconcileGroupMeshSync(channelId)

        assertTrue(nodeM01.runtime.testIsCanonicalGroupMember(sessionId, "M02"))
        assertFalse(nodeM01.runtime.testHasFormerlyAdmittedPeer(sessionId, "M02"))
        assertNoLogEventsSince(
            nodeM01,
            m01LogMark,
            "GROUP_E4_REJOIN_EVALUATED",
            "GROUP_E4_REJOIN_INVITE_ISSUED",
            "GROUP_E4_REJOIN_ADMISSION_COMMITTED"
        )
    }

    @Test
    fun t9g_acceptCommitsRosterBeforeIceConnected() {
        val channelId = "T9-G-E4"
        val sessionId = bootstrapThreeNodeGroup(channelId)
        nodeM02.runtime.testDropLocalGroupSession(sessionId)
        val m01LogMark = synchronized(nodeM01.logs) { nodeM01.logs.size }

        val (_, _, epochAfterEvict) = nodeM01.runtime.testEvictAndTriggerE4Rejoin(sessionId, "M02")
        nodeM02.refreshDiscovery()
        if (!nodeM01.hasLog { it.contains("GROUP_E4_REJOIN_ADMISSION_COMMITTED") }) {
            triggerE4Rejoin(nodeM01, channelId, sessionId)
        }

        assertTrue(
            nodeM01.waitForLogSince(m01LogMark, timeoutMs = 8_000L) {
                it.contains("GROUP_E4_REJOIN_ADMISSION_COMMITTED")
            }
        )
        assertTrue(nodeM01.runtime.testRosterEpoch(sessionId) > epochAfterEvict)
        assertTrue(nodeM01.runtime.testIsCanonicalGroupMember(sessionId, "M02"))
        val ice = nodeM01.runtime.qosSnapshotForModule("M02")?.iceState
        assertFalse("ICE must not be CONNECTED before explicit connect", ice == "CONNECTED")
    }

    @Test
    fun t9i_inviteWithoutAccept_leavesEpochUnchangedAndPeerPending() {
        val channelId = "T9-I-E4"
        val sessionId = bootstrapThreeNodeGroup(channelId)
        nodeM02.runtime.testDropLocalGroupSession(sessionId)
        nodeM02.stop()
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50601, m02 to 50602)
        nodeM02 = TestTalkbackNode(
            context = context,
            moduleId = m02,
            port = 50602,
            hub = hub,
            allPeers = peers,
            autoAcceptIncoming = false
        )
        nodeM02.start()

        val m01LogMark = synchronized(nodeM01.logs) { nodeM01.logs.size }
        val (hasHistory, stillCanonical, epochAfterEvict) =
            nodeM01.runtime.testEvictGroupMemberAtomic(sessionId, "M02")
        assertTrue(hasHistory)
        assertFalse(stillCanonical)

        nodeM01.refreshDiscovery()
        nodeM02.refreshDiscovery()
        if (!nodeM01.hasLog { it.contains("GROUP_E4_REJOIN_INVITE_ISSUED") }) {
            assertTrue(nodeM01.runtime.testRunE4RejoinAdmission(sessionId, "M02"))
            triggerE4Rejoin(nodeM01, channelId, sessionId)
        }

        assertTrue(
            nodeM01.waitForLogSince(m01LogMark, timeoutMs = 8_000L) {
                it.contains("GROUP_E4_REJOIN_INVITE_ISSUED")
            }
        )
        Thread.sleep(500L)
        assertEquals(epochAfterEvict, nodeM01.runtime.testRosterEpoch(sessionId))
        assertTrue(nodeM01.runtime.testPendingInviteeModuleIds(sessionId).contains("M02"))
        assertFalse(nodeM01.runtime.testIsCanonicalGroupMember(sessionId, "M02"))
        assertFalse(
            nodeM01.hasLog { it.contains("GROUP_E4_REJOIN_ADMISSION_COMMITTED") }
        )
    }

    private fun bootstrapThreeNodeGroup(channelId: String): String {
        nodeM01.runtime.configureChannelMembership(channelId, listOf("M01", "M02"))
        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(EndpointAddress(m02, EndpointId("E01"))),
                channelId
            )
        )
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        return sessionId
    }

    private fun evictM02AndDropLocalSession(sessionId: String): Long {
        assertTrue(
            "M02 must be canonical before prune",
            nodeM01.runtime.testIsCanonicalGroupMember(sessionId, "M02")
        )
        val (hasHistory, stillCanonical, epochAfterEvict) =
            nodeM01.runtime.testEvictGroupMemberAtomic(sessionId, "M02")
        assertFalse(
            "M02 still canonical after prune members=${nodeM01.runtime.testGroupMemberModuleIds(sessionId)}",
            stillCanonical
        )
        assertTrue(hasHistory)
        nodeM02.runtime.testDropLocalGroupSession(sessionId)
        return epochAfterEvict
    }

    private fun triggerE4Rejoin(
        authority: TestTalkbackNode,
        channelId: String,
        sessionId: String,
        reachableModuleId: String = "M02"
    ) {
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            authority.runtime.reconcileGroupMeshSync(channelId)
            authority.runtime.testRunE4RejoinAdmission(sessionId, reachableModuleId)
            if (authority.hasLog { it.contains("GROUP_E4_REJOIN_INVITE_ISSUED") }) return
            Thread.sleep(50L)
        }
    }

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
