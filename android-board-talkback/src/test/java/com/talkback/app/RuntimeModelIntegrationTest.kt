package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.SessionDisposition
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

/**
 * ADR-0001: Group active → Unicast preempt → hangup → Group resumes (not destroyed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RuntimeModelIntegrationTest {
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
        val peers = TestTalkbackNode.allPeers(m01 to 50401, m02 to 50402, m03 to 50403)
        nodeM01 = TestTalkbackNode(context, m01, 50401, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50402, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50403, hub, peers)
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
    fun groupActive_unicastPreempts_groupResumesAfterHangup() {
        val channelId = "RUNTIME-MODEL"
        val groupSessionId = requireNotNull(
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
        Thread.sleep(300L)
        assertEquals(groupSessionId, nodeM03.runtime.activeSessionIds().single())

        val unicastSessionId = nodeM03.runtime.call(
            nodeM03.localEndpoint,
            EndpointAddress(m02, EndpointId("E01"))
        )
        assertTrue(nodeM03.waitForLog { it.contains("Outgoing call") })
        assertTrue(
            waitForDisposition(nodeM03, groupSessionId, SessionDisposition.SUSPENDED)
        )
        val modulePresence = nodeM03.runtime.modulePresenceSnapshot()
        assertEquals(unicastSessionId, modulePresence.stackTopSessionId)

        nodeM03.runtime.hangup(unicastSessionId)
        assertTrue(
            waitForDisposition(nodeM03, groupSessionId, SessionDisposition.ACTIVE)
        )
        assertTrue(nodeM03.runtime.activeSessionIds().contains(groupSessionId))
        assertNotNull(nodeM03.runtime.sessionPresenceSnapshot(groupSessionId))
    }

    private fun waitForDisposition(
        node: TestTalkbackNode,
        sessionId: String,
        expected: SessionDisposition
    ): Boolean {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val snap = node.runtime.sessionPresenceSnapshot(sessionId)
            if (snap?.disposition == expected) return true
            Thread.sleep(50)
        }
        return false
    }
}
