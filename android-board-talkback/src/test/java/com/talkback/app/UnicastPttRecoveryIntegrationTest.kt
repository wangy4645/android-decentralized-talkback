package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupRoomId
import com.talkback.core.session.SessionType
import com.talkback.core.session.UnicastCallPhase
import com.talkback.core.signaling.InMemorySignalingHub
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
class UnicastPttRecoveryIntegrationTest {
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
        val peers = TestTalkbackNode.allPeers(m01 to 50031, m02 to 50032, m03 to 50033)
        nodeM01 = TestTalkbackNode(context, m01, 50031, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50032, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50033, hub, peers)
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
    fun groupCall_usesDeterministicRoomId() {
        val channelId = "UNICAST-RECOVERY-CH"
        val expectedId = GroupRoomId.forChannel(channelId)
        val sessionId = nodeM01.runtime.groupCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertEquals(expectedId, sessionId)
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("invite accepted") })
    }

    @Test
    fun unicastAfterGroup_suspendsAndResumesGroupOnSameRoomId() {
        val channelId = "UNICAST-PTT-CH"
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
        val connectedDeadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < connectedDeadline) {
            val phase = nodeM01.runtime.sessionSnapshots()
                .firstOrNull { it.sessionId == unicastId }
                ?.callPhase
            if (phase == UnicastCallPhase.CONNECTED) break
            Thread.sleep(50L)
        }
        assertEquals(
            UnicastCallPhase.CONNECTED,
            nodeM01.runtime.sessionSnapshots().firstOrNull { it.sessionId == unicastId }?.callPhase
        )

        nodeM01.runtime.hangup(unicastId)
        assertTrue(nodeM01.waitForLog(timeoutMs = 8_000L) { it.contains("Resuming GROUP session after unicast") })

        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            val ids = nodeM01.runtime.activeSessionIds()
            if (ids == listOf(roomId)) break
            Thread.sleep(50L)
        }
        assertEquals(listOf(roomId), nodeM01.runtime.activeSessionIds())
        assertFalse(
            nodeM01.hasLog { it.contains("Mesh invite rejected") && it.contains("BUSY (session kept)") }
        )

        nodeM01.runtime.simulateRemoteIceState("M02", "CONNECTED")
        nodeM01.runtime.simulateRemoteIceState("M03", "CONNECTED")
        assertTrue(
            nodeM01.runtime.qosSnapshotForModule("M02")?.iceState == "CONNECTED"
        )
    }

    @Test
    fun threeNodeGroup_usesMeshTopologyDuringDtmDiagnostic() {
        val channelId = "ANCHOR-3-CH"
        val sessionId = nodeM01.runtime.groupCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertNotNull(sessionId)
        assertTrue(nodeM01.waitForLog { it.contains("topology=MESH") })
    }
}
