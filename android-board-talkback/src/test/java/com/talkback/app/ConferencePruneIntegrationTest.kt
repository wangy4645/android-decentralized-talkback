package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.InviteState
import com.talkback.core.session.SessionType
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
class ConferencePruneIntegrationTest {
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
        val peers = TestTalkbackNode.allPeers(m01 to 50051, m02 to 50052, m03 to 50053)
        nodeM01 = TestTalkbackNode(context, m01, 50051, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50052, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50053, hub, peers)
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
    fun conferencePrune_doesNotRemoveInvitedOnlyPeerWithStaleIce() {
        val channelId = "CONF-PRUNE-INVITED"
        nodeM02.runtime.setAutoAcceptConferenceInvites(true)
        val sessionId = nodeM01.runtime.conferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertNotNull(sessionId)
        assertTrue(
            nodeM02.waitForLog(timeoutMs = 8_000L) {
                it.contains("Conference invite accepted") || it.contains("invite accepted")
            }
        )
        val before = nodeM02.runtime.sessionSnapshots().first { it.sessionId == sessionId }
        assertTrue(before.memberKeys.any { it.startsWith("M03") })

        nodeM02.runtime.simulateRemoteIceState("M03", "CLOSED")
        nodeM02.runtime.testRunConferenceHealthCleanup(channelId)

        assertFalse(nodeM02.hasLog { it.contains("Pruning unhealthy conference peer M03") })
        val after = nodeM02.runtime.sessionSnapshots().first { it.sessionId == sessionId }
        assertTrue(after.memberKeys.any { it.startsWith("M03") })
        val m03View = after.memberViews.firstOrNull { it.moduleId == "M03" }
        assertTrue(m03View == null || m03View.invite != InviteState.ACCEPTED)
    }

    @Test
    fun conferenceLateJoin_restoresRosterAfterMeshLinkAccepted() {
        val channelId = "CONF-LATE-JOIN"
        nodeM02.runtime.setAutoAcceptConferenceInvites(true)
        nodeM03.runtime.setAutoAcceptConferenceInvites(true)
        val sessionId = nodeM01.runtime.conferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertNotNull(sessionId)
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("invite accepted") })

        nodeM02.runtime.simulateRemoteIceState("M03", "CLOSED")
        nodeM02.runtime.testRunConferenceHealthCleanup(channelId)
        assertFalse(nodeM02.hasLog { it.contains("Pruning unhealthy conference peer M03") })

        nodeM03.runtime.simulateRemoteIceState("M02", "CONNECTED")
        nodeM02.runtime.simulateRemoteIceState("M03", "CONNECTED")
        Thread.sleep(500L)

        val snapshot = nodeM02.runtime.sessionSnapshots().first { it.sessionId == sessionId }
        assertEquals(SessionType.CONFERENCE, snapshot.type)
        assertTrue(snapshot.memberKeys.any { it.startsWith("M03") })
    }
}
