package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.ChannelReadiness
import com.talkback.core.session.SessionType
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ChannelWarmupIntegrationTest {
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
        val peers = TestTalkbackNode.allPeers(m01 to 50021, m02 to 50022, m03 to 50023)
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
    }

    @Test
    fun discoveryOnly_channelReadinessShowsDirectorySync() {
        val readiness = nodeM01.runtime.channelReadiness("DISCOVERY-ONLY")
        assertEquals(ChannelReadiness.DIRECTORY_SYNC, readiness)
    }

    @Test
    fun channelReadiness_progressesWithoutManualPtt() {
        val channelId = "WARMUP-CH"
        val sessionId = nodeM01.runtime.groupCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertNotNull(sessionId)
        val readiness = nodeM01.runtime.channelReadiness(channelId)
        assertTrue(
            readiness == ChannelReadiness.CONNECTING ||
                readiness == ChannelReadiness.READY ||
                readiness == ChannelReadiness.DIRECTORY_SYNC
        )
    }

    @Test
    fun groupCall_withoutPriorHello_usesDefaultEndpoint() {
        val sessionId = nodeM01.runtime.groupCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m02, EndpointId("E01"))),
            "NO-HELLO-CH"
        )
        assertNotNull(sessionId)
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
        assertEquals(SessionType.GROUP, nodeM02.runtime.sessionSnapshots().first().type)
    }

    @Test
    fun groupMeshJoin_nonHostJoinRequest_pulledIntoHostMesh() {
        val channelId = "JOIN-VIA-HOST"
        val hostSessionId = nodeM01.runtime.groupCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m03, EndpointId("E01"))),
            channelId
        )
        assertNotNull(hostSessionId)
        assertTrue(nodeM03.waitForLog { it.contains("invite accepted") })
        Thread.sleep(2_000L)

        nodeM02.runtime.reconcileGroupMesh(channelId)
        nodeM01.runtime.reconcileGroupMesh(channelId)
        assertTrue(
            nodeM01.waitForLog(timeoutMs = 8_000L) {
                it.contains("Counter-invited M02 into group mesh sent=1") ||
                    it.contains("Group invite sent -> M02")
            }
        )

        val deadline = System.currentTimeMillis() + 8_000L
        var m02OnHostSession = false
        while (System.currentTimeMillis() < deadline) {
            if (nodeM02.runtime.activeSessionIds() == listOf(hostSessionId)) {
                m02OnHostSession = true
                break
            }
            Thread.sleep(50L)
        }
        assertTrue("M02 should join host session $hostSessionId", m02OnHostSession)
    }

    @Test
    fun reconcileGroupMesh_latePeerPulledIntoBootstrapMesh() {
        val channelId = "LATE-JOIN"
        val hostSessionId = nodeM01.runtime.groupCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m03, EndpointId("E01"))),
            channelId
        )
        assertNotNull(hostSessionId)
        assertTrue(nodeM03.waitForLog { it.contains("invite accepted") })
        Thread.sleep(1_500L)

        nodeM01.runtime.reconcileGroupMesh(channelId)
        nodeM02.runtime.reconcileGroupMesh(channelId)
        assertTrue(
            nodeM01.waitForLog(timeoutMs = 8_000L) {
                it.contains("Pairwise re-invited M02") || it.contains("Group invite sent -> M02")
            }
        )

        val deadline = System.currentTimeMillis() + 8_000L
        var m02OnHostSession = false
        while (System.currentTimeMillis() < deadline) {
            if (nodeM02.runtime.activeSessionIds() == listOf(hostSessionId)) {
                m02OnHostSession = true
                break
            }
            Thread.sleep(50L)
        }
        assertTrue("M02 should join bootstrap session $hostSessionId", m02OnHostSession)
    }

    @Test
    fun reconcileGroupMesh_bootstrapUsesPairwiseInvitesOnly() {
        val channelId = "PAIRWISE-BOOT"
        nodeM01.runtime.reconcileGroupMesh(channelId)
        val sessionId = nodeM01.runtime.sessionSnapshotForChannel(channelId)?.sessionId
        assertNotNull(sessionId)
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("invite accepted") })
        assertEquals(listOf(sessionId), nodeM02.runtime.activeSessionIds())
        assertEquals(listOf(sessionId), nodeM03.runtime.activeSessionIds())
    }
}
