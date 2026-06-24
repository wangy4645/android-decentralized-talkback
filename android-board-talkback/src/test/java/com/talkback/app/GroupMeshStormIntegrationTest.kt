package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupRoomId
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
class GroupMeshStormIntegrationTest {
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
        val peers = TestTalkbackNode.allPeers(m01 to 50061, m02 to 50062, m03 to 50063)
        nodeM01 = TestTalkbackNode(context, m01, 50061, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50062, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50063, hub, peers)
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
    fun groupReconnect_usesJoinNotInviteReconnect() {
        val channelId = "STORM-JOIN-CH"
        nodeM01.runtime.groupCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
        Thread.sleep(1_500L)
        nodeM01.runtime.refreshStaleGroupSession(channelId)
        Thread.sleep(2_500L)
        assertFalse(
            nodeM01.hasLog { it.contains("Group reconnect invite sent") }
        )
    }

    @Test
    fun outgoingUnicast_afterGroup_suspendsPlaybackAndResumes() {
        val channelId = "OUTGOING-CH"
        val roomId = GroupRoomId.forChannel(channelId)
        nodeM01.runtime.groupCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("invite accepted") })
        Thread.sleep(1_000L)

        val unicastId = nodeM02.runtime.call(
            nodeM02.localEndpoint,
            EndpointAddress(m03, EndpointId("E01")),
            channelId
        )
        assertNotNull(unicastId)
        assertTrue(nodeM02.waitForLog { it.contains("Outgoing call") })
        assertTrue(nodeM02.waitForLog { it.contains("Suspending GROUP session for unicast") })

        nodeM02.runtime.hangup(unicastId)
        assertTrue(nodeM02.waitForLog(timeoutMs = 8_000L) { it.contains("Resuming GROUP session after unicast") })
        assertEquals(listOf(roomId), nodeM02.runtime.activeSessionIds())
    }

    @Test
    fun remoteHangupOnUnicast_resumesGroup() {
        val channelId = "REMOTE-HANGUP-CH"
        nodeM01.runtime.groupCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
        Thread.sleep(800L)

        val unicastId = nodeM03.runtime.call(
            nodeM03.localEndpoint,
            EndpointAddress(m02, EndpointId("E01")),
            channelId
        )
        assertNotNull(unicastId)
        assertTrue(nodeM02.waitForLog { it.contains("Incoming call") })
        assertTrue(nodeM02.waitForLog(timeoutMs = 8_000L) { it.contains("Call accepted") })

        nodeM03.runtime.hangup(unicastId)
        assertTrue(nodeM02.waitForLog(timeoutMs = 8_000L) { it.contains("Resuming GROUP session after unicast") })
    }
}
