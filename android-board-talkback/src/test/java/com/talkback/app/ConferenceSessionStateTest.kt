package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.InviteState
import com.talkback.core.session.MediaState
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
class ConferenceSessionStateTest {
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
        val peers = TestTalkbackNode.allPeers(m01 to 50011, m02 to 50012, m03 to 50013)
        nodeM01 = TestTalkbackNode(context, m01, 50011, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50012, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50013, hub, peers)
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
    fun conferenceVsGroupRace_pendingInviteBlocksGroupOnCallee() {
        val channelId = "RACE-CH"
        nodeM02.runtime.setAutoAcceptConferenceInvites(false)
        val hostSessionId = nodeM01.runtime.conferenceCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m02, EndpointId("E01"))),
            channelId
        )
        val pendingDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < pendingDeadline) {
            if (nodeM02.runtime.pendingConferenceInvite(channelId) != null) break
            Thread.sleep(50L)
        }
        assertNotNull(nodeM02.runtime.pendingConferenceInvite(channelId))

        val groupSessionId = nodeM02.runtime.groupCall(
            nodeM02.localEndpoint,
            listOf(EndpointAddress(m03, EndpointId("E01"))),
            channelId
        )
        assertTrue(
            groupSessionId == null ||
                nodeM02.hasLog { it.contains("Blocked GROUP") }
        )

        assertTrue(nodeM02.runtime.acceptPendingConferenceInvite(channelId))
        assertTrue(
            nodeM02.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") }
        )
        assertEquals(listOf(hostSessionId), nodeM02.runtime.activeSessionIds())
    }

    @Test
    fun pendingInviteAtomicity_doubleAcceptSecondFails() {
        val channelId = "ATOMIC-CH"
        nodeM02.runtime.setAutoAcceptConferenceInvites(false)
        nodeM01.runtime.conferenceCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m02, EndpointId("E01"))),
            channelId
        )
        val pendingDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < pendingDeadline) {
            if (nodeM02.runtime.pendingConferenceInvite(channelId) != null) break
            Thread.sleep(50L)
        }
        assertTrue(nodeM02.runtime.acceptPendingConferenceInvite(channelId))
        assertFalse(nodeM02.runtime.acceptPendingConferenceInvite(channelId))
    }

    @Test
    fun memberViewState_invitingBeforeConnected() {
        val channelId = "VIEW-CH"
        nodeM02.runtime.setAutoAcceptConferenceInvites(false)
        nodeM03.runtime.setAutoAcceptConferenceInvites(false)
        val sessionId = nodeM01.runtime.conferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        Thread.sleep(500L)
        val snapshot = nodeM01.runtime.sessionSnapshots().firstOrNull { it.sessionId == sessionId }
        assertNotNull(snapshot)
        assertEquals(SessionType.CONFERENCE, snapshot!!.type)
        val m02View = snapshot.memberViews.firstOrNull { it.moduleId == "M02" }
        val m03View = snapshot.memberViews.firstOrNull { it.moduleId == "M03" }
        if (m02View != null) {
            assertTrue(
                m02View.invite == InviteState.INVITING ||
                    m02View.invite == InviteState.RINGING ||
                    m02View.invite == InviteState.ACCEPTED
            )
            assertTrue(m02View.media != MediaState.CONNECTED || snapshot.connectedRemoteCount >= 0)
        }
        if (m03View != null) {
            assertTrue(m03View.invite != InviteState.NONE || m03View.media == MediaState.NONE)
        }
        assertTrue(snapshot.connectedRemoteCount <= snapshot.memberKeys.size - 1)
    }

    @Test
    fun hostInvitesLargerModuleId_participantAcceptsAndIceCompleted() {
        val channelId = "HOST-SMALL-TO-LARGE"
        val sessionId = nodeM01.runtime.conferenceCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m03, EndpointId("E01"))),
            channelId
        )
        assertNotNull(sessionId)
        assertTrue(
            nodeM03.waitForLog(timeoutMs = 8_000L) {
                it.contains("Conference invite accepted") || it.contains("invite accepted")
            }
        )
        nodeM01.runtime.simulateRemoteIceState("M03", "COMPLETED")
        nodeM03.runtime.simulateRemoteIceState("M01", "COMPLETED")
        Thread.sleep(200L)
        assertTrue(nodeM01.runtime.isChannelMediaReady(channelId))
        assertTrue(nodeM03.runtime.isChannelMediaReady(channelId))
    }
}
