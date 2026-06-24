package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupRoomId
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
class SessionFsmConvergenceIntegrationTest {
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
        val peers = TestTalkbackNode.allPeers(m01 to 50041, m02 to 50042, m03 to 50043)
        nodeM01 = TestTalkbackNode(context, m01, 50041, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50042, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50043, hub, peers)
        nodeM01.start()
        nodeM02.start()
        nodeM03.start()
        Thread.sleep(300L)
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        nodeM03.stop()
    }

    @Test
    fun authorityMerge_yieldToMembershipAuthority_notBusy() {
        val channelId = "CH-AUTH-MERGE"
        val canonicalId = GroupRoomId.forChannel(channelId)
        nodeM03.runtime.testSeedDuplicateGroupSession(
            channelId = channelId,
            sessionId = "zzz-m03-dup",
            initiatorModuleId = "M03",
            connectedPeerModuleIds = emptyList()
        )
        assertTrue(nodeM03.runtime.activeSessionIds().contains("zzz-m03-dup"))

        val injected = nodeM03.runtime.testInjectGroupInvite(
            callerModuleId = "M01",
            channelId = channelId,
            sessionId = canonicalId,
            initiatorModuleId = "M04",
            fromPeer = TestTalkbackNode.peerTarget(50041),
            memberModuleIds = listOf("M01", "M02", "M03")
        )
        assertTrue(injected)

        assertTrue(
            nodeM03.waitForLog(timeoutMs = 8_000L) {
                it.contains("Yielding duplicate session to membership authority=M01") &&
                    it.contains("channel=$channelId")
            }
        )
        assertTrue(nodeM03.waitForLog(timeoutMs = 8_000L) { it.contains("invite accepted") })
        assertFalse(
            nodeM03.hasLog {
                it.contains("Keeping canonical group mesh, rejecting duplicate invite")
            }
        )
        assertFalse(nodeM03.hasLog { it.contains("Counter-invited") })
        assertEquals(listOf(canonicalId), nodeM03.runtime.activeSessionIds())
    }

    @Test
    fun authorityMerge_preservesGroupMediaEngine() {
        val channelId = "CH-AUTH-MEDIA"
        val canonicalId = GroupRoomId.forChannel(channelId)
        nodeM03.runtime.testSeedDuplicateGroupSession(
            channelId = channelId,
            sessionId = "zzz-m03-dup",
            initiatorModuleId = "M03",
            connectedPeerModuleIds = listOf("M01")
        )
        assertTrue(nodeM03.runtime.hasGroupMediaEngine("M01"))

        nodeM03.runtime.testInjectGroupInvite(
            callerModuleId = "M01",
            channelId = channelId,
            sessionId = canonicalId,
            initiatorModuleId = "M04",
            fromPeer = TestTalkbackNode.peerTarget(50041),
            memberModuleIds = listOf("M01", "M02", "M03")
        )
        assertTrue(
            nodeM03.waitForLog(timeoutMs = 8_000L) {
                it.contains("Yielding duplicate session to membership authority=M01")
            }
        )
        assertTrue(nodeM03.runtime.hasGroupMediaEngine("M01"))
        assertTrue(nodeM03.waitForLog(timeoutMs = 8_000L) { it.contains("invite accepted") })
        assertEquals(listOf(canonicalId), nodeM03.runtime.activeSessionIds())
    }

    @Test
    fun unicastResume_schedulesPostUnicastMeshRetry() {
        val channelId = "CH-RESUME-RETRY"
        val roomId = GroupRoomId.forChannel(channelId)
        val groupSessionId = nodeM01.runtime.groupCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertNotNull(groupSessionId)
        assertEquals(roomId, groupSessionId)
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("invite accepted") })
        Thread.sleep(1_500L)

        val unicastId = nodeM01.runtime.call(
            nodeM01.localEndpoint,
            EndpointAddress(m02, EndpointId("E01")),
            channelId
        )
        assertNotNull(unicastId)
        assertTrue(nodeM01.waitForLog { it.contains("Suspending GROUP session for unicast") })
        assertTrue(nodeM02.waitForLog(timeoutMs = 8_000L) { it.contains("Call accepted") })

        nodeM01.runtime.simulateUnicastIceState(unicastId!!, "CONNECTED")
        nodeM02.runtime.simulateUnicastIceState(unicastId, "CONNECTED")
        nodeM01.runtime.hangup(unicastId)
        assertTrue(nodeM01.waitForLog(timeoutMs = 8_000L) { it.contains("Resuming GROUP session after unicast") })

        nodeM01.runtime.simulateRemoteIceState("M02", "DISCONNECTED")
        nodeM01.runtime.simulateRemoteIceState("M03", "DISCONNECTED")
        assertTrue(
            nodeM01.waitForLog(timeoutMs = 4_000L) { it.contains("Post-unicast mesh retry") }
        )

        nodeM01.runtime.simulateRemoteIceState("M02", "CONNECTED")
        nodeM01.runtime.simulateRemoteIceState("M03", "CONNECTED")
        assertEquals(
            "CONNECTED",
            nodeM01.runtime.qosSnapshotForModule("M02")?.iceState
        )
        assertFalse(
            nodeM01.hasLog { it.contains("Mesh invite rejected") && it.contains("BUSY (session kept)") }
        )
    }
}
