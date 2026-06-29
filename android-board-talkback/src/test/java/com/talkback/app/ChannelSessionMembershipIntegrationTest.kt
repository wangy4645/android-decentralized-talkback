package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ADR-0002: Session membership changes must not write back to Channel config (R7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ChannelSessionMembershipIntegrationTest {
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
        val peers = TestTalkbackNode.allPeers(m01 to 50501, m02 to 50502, m03 to 50503)
        nodeM01 = TestTalkbackNode(context, m01, 50501, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50502, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50503, hub, peers)
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
    fun evictFromSession_doesNotMutateChannelConfig() {
        val channelId = "CH-R7"
        nodeM01.runtime.configureChannelMembership(channelId, listOf("M01", "M02", "M03"))
        val sessionId = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(
                    EndpointAddress(m02, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
        )
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })

        nodeM01.runtime.testEvictGroupMember(sessionId, "M03")

        assertEquals(setOf("M01", "M02", "M03"), nodeM01.runtime.channelMemberModuleIds(channelId))
        val snap = requireNotNull(nodeM01.runtime.sessionSnapshots().first { it.sessionId == sessionId })
        assertFalse(snap.memberKeys.any { it.startsWith("M03-") })
        assertEquals(listOf("M01", "M02", "M03"), snap.channelMemberModuleIds)
    }

    @Test
    fun twoSessions_sameChannel_differentRuntimeRoster() {
        val channelId = "CH-R8"
        nodeM01.runtime.configureChannelMembership(channelId, listOf("M01", "M02", "M03"))

        val session1 = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(
                    EndpointAddress(m02, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
        )
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        nodeM01.runtime.testEvictGroupMember(session1, "M03")
        val snap1 = requireNotNull(nodeM01.runtime.sessionSnapshots().first { it.sessionId == session1 })
        assertEquals(listOf("M01", "M02", "M03"), snap1.channelMemberModuleIds)
        assertFalse(snap1.memberKeys.any { it.startsWith("M03-") })

        nodeM01.runtime.hangup(session1)
        Thread.sleep(300L)

        val session2 = requireNotNull(
            nodeM01.runtime.groupCall(
                nodeM01.localEndpoint,
                listOf(EndpointAddress(m02, EndpointId("E01"))),
                channelId
            )
        )
        val snap2 = requireNotNull(nodeM01.runtime.sessionSnapshots().first { it.sessionId == session2 })
        assertEquals(listOf("M01", "M02", "M03"), snap2.channelMemberModuleIds)
        assertTrue(snap2.memberKeys.any { it.startsWith("M03-") })
        assertNotEquals(snap1.memberKeys.toSet(), snap2.memberKeys.toSet())
    }

    @Test
    fun unicast_doesNotJoinChannelMembership() {
        val channelId = "CH-ORIGIN"
        nodeM01.runtime.configureChannelMembership(channelId, listOf("M01", "M02"))
        val before = nodeM01.runtime.channelMemberModuleIds(channelId)

        nodeM01.runtime.call(
            nodeM01.localEndpoint,
            EndpointAddress(m02, EndpointId("E01")),
            channelId
        )
        assertTrue(nodeM01.waitForLog { it.contains("Outgoing call") })
        assertEquals(before, nodeM01.runtime.channelMemberModuleIds(channelId))
    }
}
