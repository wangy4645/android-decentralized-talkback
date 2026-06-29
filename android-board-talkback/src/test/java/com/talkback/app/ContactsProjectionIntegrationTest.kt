package com.talkback.app

import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointPriority
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ContactsProjectionIntegrationTest {
    private val m01 = com.talkback.core.model.ModuleId("M01")
    private val m02 = com.talkback.core.model.ModuleId("M02")
    private val hub = com.talkback.core.signaling.InMemorySignalingHub()
    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM02: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(
            m01 to 50001,
            m02 to 50002
        )
        nodeM01 = TestTalkbackNode(context, m01, 50001, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50002, hub, peers)
        nodeM02.runtime.upsertLocalEndpoint(EndpointId("E02"), "Headset", online = true, priority = EndpointPriority.NORMAL)
        nodeM01.start()
        nodeM02.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
    }

    @Test
    fun peerDisplayRoster_afterHello_showsAllRemoteEndpoints() {
        assertTrue(nodeM01.waitForLog { it.contains("HELLO from M02") })
        assertTrue(nodeM02.waitForLog { it.contains("HELLO from M01") })
        Thread.sleep(200L)

        val roster = nodeM01.runtime.peerDisplayRoster()
        val keys = roster.map { it.endpointKey }.sorted()
        assertEquals(listOf("M02-E01", "M02-E02"), keys)
        assertEquals(2, roster.size)
    }

    @Test
    fun unicastCall_stillWorksAfterContactsGate() {
        assertTrue(nodeM01.waitForLog { it.contains("HELLO from M02") })
        val sessionId = nodeM01.runtime.call(
            nodeM01.localEndpoint,
            com.talkback.core.model.EndpointAddress(m02, EndpointId("E01"))
        )
        assertTrue(nodeM01.waitForLog { it.contains("Outgoing call") })
        assertTrue(nodeM02.waitForLog { it.contains("Call accepted") })
        assertEquals(listOf(sessionId), nodeM01.runtime.activeSessionIds())
    }
}
